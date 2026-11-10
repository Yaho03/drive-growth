package io.drivegrowth.app.host.driving

import android.database.sqlite.SQLiteDatabase
import io.drivegrowth.app.data.DriveCommit
import io.drivegrowth.app.host.GameIdentity
import io.drivegrowth.core.contract.ContractResult

/** E가 공용 DB에 구현할 포트. 호출은 같은 store.atomic 내부이며 별도 연결/commit을 열지 않는다.
 * 최초 기능 marker를 명시 생성한 실제 profile만 초기 빈 state를 가진다. 기존 missing/corrupt는 Failure다.
 * save는 revision CAS, recordCommit은 driveId별 원본 전체의 동일성/재생과 실제 행 쓰기를 검사한다.
 * 모든 collection/decimal/calendar/rule/reason은 엄격하게 직렬화하고 재개 시 receiver를 재사용하지 않는다.
 */
interface ObservedDrivingJournal {
 fun read(db:SQLiteDatabase,identity:GameIdentity):ContractResult<RecordingState>
 fun save(db:SQLiteDatabase,identity:GameIdentity,expectedRevision:Long,next:RecordingState):ContractResult<Unit>
 fun recordCommit(db:SQLiteDatabase,identity:GameIdentity,receipt:ObservedDrivingReceipt):ContractResult<Unit>
 fun committed(db:SQLiteDatabase,identity:GameIdentity,driveId:String):ContractResult<ObservedDrivingReceipt?>
}
data class ObservedDrivingReceipt(val trip:ObservedTrip,val commit:DriveCommit)
data class DrivingRecordingResult(val state:RecordingState,val committed:List<ObservedDrivingReceipt>,val waiting:io.drivegrowth.core.contract.ContractError?)
