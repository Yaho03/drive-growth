package io.drivegrowth.app.host.driving

import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.app.host.GameSession
import io.drivegrowth.app.host.GameSessionFactory
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.driving.DrivingIncomeRules

/** 호출자의 단일 worker에서 관측 순서대로 실행한다. 같은 factory/profile/source/store를 사용한다. */
class SessionObservedDrivingSource(
 private val games:GameSessionFactory,
 private val journal:ObservedDrivingJournal,
 private val reducer:ObservedDrivingReducer,
 private val rules:()->ContractResult<DrivingIncomeRules>,
) {
 fun observe(receiver:String,frame:DrivingFrame,now:RecordingClock):ContractResult<DrivingRecordingResult> =
  withSession { session,db,state ->
   when(val next=reducer.observe(state,receiver,frame,now,rules())) {
    is ContractResult.Failure -> next
    is ContractResult.Success -> persistAndDrain(session,db,state,next.value)
   }
  }
 fun pause(receiver:String,at:RecordingClock):ContractResult<DrivingRecordingResult> =
  withSession { session,db,state ->
   when(val next=reducer.pause(state,receiver,at)) {
    is ContractResult.Failure -> next
    is ContractResult.Success -> persistAndDrain(session,db,state,next.value)
   }
  }
 private fun withSession(action:(GameSession,SQLiteDatabase,RecordingState)->ContractResult<DrivingRecordingResult>):ContractResult<DrivingRecordingResult> {
  val session=when(val opened=games.open()) {is ContractResult.Failure->return opened;is ContractResult.Success->opened.value}
  return session.use {
   session.store.atomic {db ->
    when(val loaded=journal.read(db,session.identity)) {
     is ContractResult.Failure -> loaded
     is ContractResult.Success -> if(loaded.value.identity!=session.identity||
      loaded.value.trip?.let{it.source!=session.identity.source}==true||
      loaded.value.anchor?.let{it.source!=session.identity.source}==true||
      loaded.value.outbox.any{it.source!=session.identity.source}) ContractResult.Failure(ContractError.STORAGE_FAILURE)
      else action(session,db,loaded.value)
    }
   }
  }
 }
 private fun save(session:GameSession,db:SQLiteDatabase,previous:RecordingState,next:RecordingState):ContractResult<Unit> {
  if(previous==next)return ContractResult.Success(Unit)
  if(previous.revision==Long.MAX_VALUE||next.identity!=previous.identity||next.revision!=previous.revision+1)
   return ContractResult.Failure(ContractError.STATE_CONFLICT)
  when(val saved=journal.save(db,session.identity,previous.revision,next)) {is ContractResult.Failure->return saved;is ContractResult.Success->Unit}
  return when(val read=journal.read(db,session.identity)) {
   is ContractResult.Failure -> read
   is ContractResult.Success -> if(read.value==next)ContractResult.Success(Unit) else ContractResult.Failure(ContractError.STORAGE_FAILURE)
  }
 }
 private fun persistAndDrain(session:GameSession,db:SQLiteDatabase,previous:RecordingState,next:RecordingState):ContractResult<DrivingRecordingResult> {
  when(val saved=save(session,db,previous,next)){is ContractResult.Failure->return saved;is ContractResult.Success->Unit}
  var state=next
  val committed=mutableListOf<ObservedDrivingReceipt>()
  for(trip in next.outbox) {
   val originalRules=when(val r=trip.rules) {
    is ContractResult.Failure -> return ContractResult.Success(DrivingRecordingResult(state,committed.toList(),r.error))
    is ContractResult.Success -> r.value
   }
   if(originalRules.dailyCapMeters!=500000L)return ContractResult.Failure(ContractError.INVALID_INPUT)
   val drive=try {trip.confirmed(state.identity.source)} catch(_:ArithmeticException){return ContractResult.Failure(ContractError.INVALID_INPUT)}
    ?:return ContractResult.Success(DrivingRecordingResult(state,committed.toList(),ContractError.DATA_UNAVAILABLE))
   val result=when(val paid=session.store.driving.settle(originalRules,drive)) {is ContractResult.Failure->return paid;is ContractResult.Success->paid.value}
   // 두 번째 조회는 Ledger 자신의 원명령/정산/일누계 검증 경로를 다시 통과한다.
   when(val replay=session.store.driving.settle(originalRules,drive)) {
    is ContractResult.Failure->return replay
    is ContractResult.Success->if(replay.value!=result)return ContractResult.Failure(ContractError.STORAGE_FAILURE)
   }
   val receipt=ObservedDrivingReceipt(trip,result)
   when(val recorded=journal.recordCommit(db,session.identity,receipt)){is ContractResult.Failure->return recorded;is ContractResult.Success->Unit}
   when(val read=journal.committed(db,session.identity,trip.id)) {
    is ContractResult.Failure->return read
    is ContractResult.Success->if(read.value!=receipt)return ContractResult.Failure(ContractError.STORAGE_FAILURE)
   }
   if(state.revision==Long.MAX_VALUE)return ContractResult.Failure(ContractError.STATE_CONFLICT)
   val ack=state.copy(revision=state.revision+1,outbox=state.outbox.drop(1))
   when(val saved=save(session,db,state,ack)){is ContractResult.Failure->return saved;is ContractResult.Success->Unit}
   state=ack;committed+=receipt
  }
  return ContractResult.Success(DrivingRecordingResult(state,committed.toList(),null))
 }
}
