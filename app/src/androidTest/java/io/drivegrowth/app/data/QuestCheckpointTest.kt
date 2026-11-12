package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase
import android.test.InstrumentationTestCase
import io.drivegrowth.core.contract.*
import io.drivegrowth.core.decoration.*
import io.drivegrowth.core.quest.*
import java.time.Instant
import java.time.ZoneId

/** F 실제 원결과 조회/단일 typed journal 소비 경계. E 장치 실행 없이 컴파일 인계한다. */
@Suppress("DEPRECATION")
class QuestCheckpointTest:InstrumentationTestCase() {
    private val context get()=instrumentation.targetContext
    private val source=InputSource.SIMULATION
    private val zone=ZoneId.of("UTC")
    private var now=Instant.parse("2026-11-10T03:00:00Z").toEpochMilli()
    private var available=true
    private lateinit var name:String
    private lateinit var store:SqliteGameChangeStore
    private lateinit var definition:TrialDefinition
    private val profile get()=ok(store.localProfileId())
    private fun open()=SqliteGameChangeStore(context,source,name,questDefinitions={if(available)definition.questDefinitions(zone) else ContractResult.Failure(ContractError.DATA_UNAVAILABLE)},clock={now})
    private fun <T> ok(r:ContractResult<T>):T{assertTrue(r.toString(),r is ContractResult.Success);return(r as ContractResult.Success).value}
    override fun setUp(){super.setUp();definition=ok(AssetTrialDefinitionProvider(context.assets).load());name="quest-journal-${System.nanoTime()}.db";store=open()
        val receipt=ok(store.commitGameChange(GameChange(RequestId("fund"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.CREDIT,GameMoney(1000)))));ok(store.markViewed(receipt.settlementId))
    }
    override fun tearDown(){store.close();context.deleteDatabase(name);super.tearDown()}
    private fun raw(f:(SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(f)
    private fun rows(vararg extra: String):List<String> = buildList{raw{db->for(t in listOf("account","settlements","quest_events","quest_tracking","quest_claims","host_command_checkpoint") + extra)db.rawQuery("SELECT * FROM $t ORDER BY 1",null).use{r->while(r.moveToNext())add(t+":"+(0 until r.columnCount).joinToString("|"){if(r.isNull(it))"<null>"else r.getString(it)})}}}
    private fun command():HostCheckpointCommand.Quest {
        val p=ok(store.quests.read(profile,Observation.Available(EpochMillis(now)))).single{it.definition.id=="daily-result-viewed"}
        return HostCheckpointCommand.Quest(QuestClaimCommand(RequestId("quest/claim/actual-journal"),profile,source,p.definition,p.period,p.revision!!))
    }
    private fun execute(c:HostCheckpointCommand.Quest):QuestClaimSettlement {ok(store.hostCommands.save(profile,c));return(ok(store.hostCommands.execute(profile,c)) as HostCheckpointResult.Quest).value}
    fun testOriginalLookupMissingIsReadOnlyAndDoesNotPrepareClaim() {
        val before=rows();assertNull(ok(store.quests.settlement(RequestId("quest/claim/missing"))));assertNull(ok(store.hostCommands.load(profile)));assertEquals(before,rows())
    }
    fun testResponseLossReopensStartedAndReplaysBeforeNewDefinitionTimeBalance() {
        val c=command();val first=execute(c);ok(store.commitGameChange(GameChange(RequestId("spend"),TransactionKind.GARAGE,source,definition.rules,MoneyChange(MoneyDirection.DEBIT,GameMoney(200)))))
        available=false;now+=8*86400000L;val before=rows();store.close();store=open();assertEquals(first,ok(store.quests.settlement(c.value.requestId)))
        assertEquals(c,ok(store.hostCommands.load(profile))!!.command);assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase)
        assertEquals(first,(ok(store.hostCommands.execute(profile,c)) as HostCheckpointResult.Quest).value);assertEquals(before,rows());assertEquals(GameMoney(900),ok(store.balance()))
        assertEquals(first,(ok(store.hostCommands.finish(profile,c)) as HostCheckpointResult.Quest).value);assertNull(ok(store.hostCommands.load(profile)))
    }
    fun testQuestAndLegacyGeneralRequestRejectCrossOwnerReuse() {
        val legacy = GameChange(RequestId("legacy-general"), TransactionKind.GARAGE, source,
            definition.rules, MoneyChange(MoneyDirection.CREDIT, GameMoney(7)))
        val receipt = ok(store.commitGameChange(legacy))
        val id = RequestId("quest/claim/legacy-general")
        // 예약 규칙이 생기기 전의 정합적인 공용 명령·결과를 재현한다.
        raw { db ->
            db.execSQL("UPDATE settlements SET request_id=?,command=?,result=? WHERE request_id=?",
                arrayOf(id.value, LedgerJson.command(legacy.copy(requestId = id)).toString(),
                    LedgerJson.settlement(receipt.copy(transaction = receipt.transaction.copy(requestId = id))).toString(),
                    legacy.requestId.value))
        }
        val legacyClaim = command().value.copy(requestId = id)
        val occupied = rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT), store.quests.claim(legacyClaim))
        assertEquals(occupied, rows())
        assertEquals(receipt.settlementId, ok(store.commitGameChange(legacy.copy(requestId = id))).settlementId)
        assertEquals(occupied, rows())

        val claim = command()
        val original = execute(claim)
        val typed = rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),
            store.commitGameChange(legacy.copy(requestId = claim.value.requestId)))
        assertEquals(original, ok(store.quests.claim(claim.value)))
        assertEquals(typed, rows())
    }

    fun testLateQuestClaimSlotResetRollsBackRewardAndKeepsSavedCommand() {
        val claim = command()
        ok(store.hostCommands.save(profile, claim))
        val digest = HostCheckpointJson.digest(HostCheckpointJson.encode(profile, claim), 0)
        raw { db ->
            db.execSQL("""
                CREATE TRIGGER reset_quest_slot AFTER INSERT ON quest_claims
                BEGIN UPDATE host_command_checkpoint SET phase=0,digest='$digest' WHERE id=1; END
            """.trimIndent())
        }
        val before = rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.hostCommands.execute(profile, claim))
        assertEquals(before, rows())
        assertEquals(GameMoney(1000), ok(store.balance()))
        assertNull(ok(store.quests.settlement(claim.value.requestId)))
        assertEquals(HostCommandCheckpoint(profile, claim, HostCheckpointPhase.SAVED), ok(store.hostCommands.load(profile)))

        raw { db ->
            db.execSQL("DROP TRIGGER reset_quest_slot")
            db.execSQL("""
                CREATE TRIGGER restore_quest_slot AFTER DELETE ON host_command_checkpoint
                BEGIN INSERT INTO host_command_checkpoint VALUES(OLD.id,OLD.payload,OLD.phase,OLD.digest); END
            """.trimIndent())
        }
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.hostCommands.cancelUnstarted(profile, claim))
        assertEquals(before, rows())

        val original = ok(store.hostCommands.execute(profile, claim))
        assertEquals(GameMoney(1100), ok(store.balance()))
        val committed = rows()
        assertEquals(original, ok(store.hostCommands.execute(profile, claim)))
        assertEquals(committed, rows())
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE), store.hostCommands.finish(profile, claim))
        assertEquals(committed, rows())
        raw { it.execSQL("DROP TRIGGER restore_quest_slot") }
        assertEquals(original, ok(store.hostCommands.finish(profile, claim)))
        assertNull(ok(store.hostCommands.load(profile)))
    }

    fun testPaidDecorationReplayAfterQuestRewardAndReopenPreservesOriginal() {
        val decorations = trialDecorationDefinitions()
        val paid = HostCheckpointCommand.Decoration(DecorationCommand(RequestId("buy-before-quest"), profile, source,
            "blue-sign", DecorationAction.BUY_AND_APPLY, decorations.ruleVersion, Revision(0), GameMoney(100)))
        raw { it.execSQL("UPDATE shop SET level=2,capacity=5 WHERE id=1") }
        ok(store.decorations.activate(profile))
        ok(store.hostCommands.save(profile, paid))
        val original = ok(store.hostCommands.execute(profile, paid)) as HostCheckpointResult.Decoration
        assertEquals(GameMoney(900), original.value.state.balance)
        assertEquals(original, ok(store.hostCommands.finish(profile, paid)))

        val claim = command()
        val reward = execute(claim)
        ok(store.hostCommands.finish(profile, claim))
        assertEquals(GameMoney(1000), reward.settlement.balance)
        store.close()
        store = open()
        store.decorationDefinitions = { ContractResult.Failure(ContractError.DATA_UNAVAILABLE) }
        val before = rows("decoration_module", "decoration_requests", "shop")
        assertEquals(original.value, ok(store.decorations.commit(paid.value)))
        assertEquals(original.value, ok(store.decorations.settlement(paid.value)))
        assertEquals(GameMoney(1000), ok(store.decorations.state(profile)).balance)
        assertEquals(before, rows("decoration_module", "decoration_requests", "shop"))
    }

    fun testFinishCannotExecuteUnstartedClaim() {
        val c=command();ok(store.hostCommands.save(profile,c));val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.finish(profile,c));assertEquals(before,rows());assertNull(ok(store.quests.settlement(c.value.requestId)))
    }
    fun testExpiredNewClaimRollsBackStartedToSavedAndCanExplicitlyCancel() {
        val c=command();ok(store.hostCommands.save(profile,c));now=c.value.period.endsAt.value;val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.execute(profile,c));assertEquals(before,rows());assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase)
        ok(store.hostCommands.cancelUnstarted(profile,c));assertNull(ok(store.hostCommands.load(profile)));assertEquals(GameMoney(1000),ok(store.balance()))
    }
    fun testStartedCannotBeCanceledOrReplacedByDifferentMeaning() {
        val c=command();execute(c);val before=rows();assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.cancelUnstarted(profile,c))
        val changed=c.copy(value=c.value.copy(definition=c.value.definition.copy(reward=GameMoney(101))))
        assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.execute(profile,changed));assertEquals(before,rows())
    }
    fun testWrongProfileAndSourceRejectedBeforeDurableSave() {
        val c=command();val before=rows()
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.hostCommands.save(profile,c.copy(value=c.value.copy(profileId=ProfileId("foreign")))))
        assertEquals(ContractResult.Failure(ContractError.INVALID_INPUT),store.hostCommands.save(profile,c.copy(value=c.value.copy(source=InputSource.VEHICLE))));assertEquals(before,rows())
    }
    fun testOtherFeatureOwnsSingleSlotUntilItsExplicitResolution() {
        val other=HostCheckpointCommand.Expand(RequestId("other-expand"),source,definition.version,definition.expansions.offers.first());ok(store.hostCommands.save(profile,other));val before=rows()
        assertEquals(other,ok(store.hostCommands.load(profile))!!.command);assertEquals(ContractResult.Failure(ContractError.STATE_CONFLICT),store.hostCommands.save(profile,command()));assertEquals(before,rows())
    }
    fun testIgnoredSavedOrStartedCannotReportDurableClaim() {
        val c=command();raw{it.execSQL("CREATE TRIGGER ignore_save BEFORE INSERT ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.save(profile,c));assertEquals(before,rows());raw{it.execSQL("DROP TRIGGER ignore_save")}
        ok(store.hostCommands.save(profile,c));raw{it.execSQL("CREATE TRIGGER ignore_start BEFORE UPDATE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val saved=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(saved,rows())
    }
    fun testRequiredMoneyReceiptMarkerWritesRollBackOuterCheckpointTogether() {
        val c=command();ok(store.hostCommands.save(profile,c))
        for((table,event)in listOf("account" to "UPDATE","settlements" to "INSERT","quest_claims" to "INSERT")){
            raw{it.execSQL("CREATE TRIGGER ignore_claim BEFORE $event ON $table BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
            assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.execute(profile,c));assertEquals(before,rows());assertEquals(HostCheckpointPhase.SAVED,ok(store.hostCommands.load(profile))!!.phase);raw{it.execSQL("DROP TRIGGER ignore_claim")}
        }
        assertEquals(GameMoney(1100),execute(c).settlement.balance)
    }
    fun testFinishDeleteFailureRetainsStartedOriginalResult() {
        val c=command();val first=execute(c);raw{it.execSQL("CREATE TRIGGER ignore_finish BEFORE DELETE ON host_command_checkpoint BEGIN SELECT RAISE(IGNORE); END")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(before,rows());assertEquals(first,ok(store.quests.settlement(c.value.requestId)))
    }
    fun testMissingClaimMarkerIsStorageFailureNotAbsentLookupOrFreshPayment() {
        val c=command();execute(c);raw{it.execSQL("DELETE FROM quest_claims")};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.quests.settlement(c.value.requestId));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(before,rows())
    }
    fun testMissingCommonReceiptIsStorageFailureAndPendingIsPreserved() {
        val c=command();execute(c);raw{it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(c.value.requestId.value))};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.quests.settlement(c.value.requestId));assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.finish(profile,c));assertEquals(before,rows())
    }
    fun testCompletedWholeCommandCanBeJournaledAgainWithoutAdditionalCredit() {
        val c=command();val first=execute(c);ok(store.hostCommands.finish(profile,c));available=false;now+=86400000L
        assertEquals(first,execute(c));ok(store.hostCommands.finish(profile,c));assertEquals(GameMoney(1100),ok(store.balance()))
    }
    fun testValidDigestFractionalRewardIsRejectedWithoutRepair() {
        val c=command();ok(store.hostCommands.save(profile,c));val encoded=HostCheckpointJson.encode(profile,c).replace("\"reward\":100","\"reward\":100.5")
        assertTrue(encoded.contains("100.5"));raw{it.execSQL("UPDATE host_command_checkpoint SET payload=?,digest=?",arrayOf(encoded,HostCheckpointJson.digest(encoded,0)))};val before=rows()
        assertEquals(ContractResult.Failure(ContractError.STORAGE_FAILURE),store.hostCommands.load(profile));assertEquals(before,rows())
    }
    fun testStartedWithLostAllOriginalRowsNeverExecutesNewClaim() {
        val c=command();execute(c);raw{it.execSQL("DELETE FROM quest_claims");it.execSQL("DELETE FROM settlements WHERE request_id=?",arrayOf(c.value.requestId.value))};val before=rows()
        assertNull(ok(store.quests.settlement(c.value.requestId)))
        assertEquals(ContractResult.Failure(ContractError.DATA_UNAVAILABLE),store.hostCommands.execute(profile,c));assertEquals(before,rows());assertEquals(GameMoney(1100),ok(store.balance()));assertEquals(HostCheckpointPhase.STARTED,ok(store.hostCommands.load(profile))!!.phase)
    }

}
