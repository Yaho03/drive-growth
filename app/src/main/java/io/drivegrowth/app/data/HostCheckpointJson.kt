package io.drivegrowth.app.data

import io.drivegrowth.core.contract.*
import org.json.JSONException
import org.json.JSONObject

/** 기존 원장 codec를 재사용한다. 가챠 private encoder와 일반 Update의 읽기만 이 경계에서 보완한다. */
internal object HostCheckpointJson {
    fun encode(profile: ProfileId, command: HostCheckpointCommand): String {
        val kind: String
        val value: JSONObject
        when (command) {
            is HostCheckpointCommand.Gacha -> { kind = "gacha"; value = gacha(command.value) }
            is HostCheckpointCommand.Garage -> { kind = "garage"; value = LedgerJson.command(command.value) }
            is HostCheckpointCommand.Material -> { kind = "material"; value = MaterialJson.command(command.value) }
            is HostCheckpointCommand.Repair -> { kind = "repair"; value = RepairJson.command(command.value) }
        }
        return JSONObject().put("type", "host-command-v1").put("profile", profile.value).put("kind", kind)
            .put("request", command.requestId.value).put("source", command.origin.name)
            .put("content", command.content.value).put("rule", command.rule.value).put("command", value).toString()
    }

    fun decode(text: String): Pair<ProfileId, HostCheckpointCommand> {
        val j = JSONObject(text)
        if (j.getString("type") != "host-command-v1") throw JSONException("checkpoint 형식 버전 오류")
        val profile = ProfileId(j.getString("profile"))
        val content = ContentVersion(j.getString("content"))
        val value = j.getJSONObject("command")
        val command = when (j.getString("kind")) {
            "gacha" -> HostCheckpointCommand.Gacha(gacha(value))
            "garage" -> HostCheckpointCommand.Garage(content, garage(value))
            "material" -> HostCheckpointCommand.Material(MaterialJson.command(value))
            "repair" -> HostCheckpointCommand.Repair(RepairJson.command(value))
            else -> throw JSONException("미지원 checkpoint 명령 종류")
        }
        if (!command.validFor(command.origin) || encode(profile, command) != text)
            throw JSONException("checkpoint 전체 명령/교차 필드 불일치")
        return profile to command
    }

    private fun gacha(c: GachaCommand) = JSONObject().put("type", "gacha-v1")
        .put("request", c.requestId.value).put("source", c.source.name).put("content", c.contentVersion.value)
        .put("rules", c.ruleVersion.value).put("revision", c.expectedRevision.value).put("shopLevel", c.shopLevel)
        .put("capacity", c.garageCapacity).put("counter", c.drawsSinceGuarantee).put("ticket", c.ticket.name)
        .put("vehicle", LedgerJson.vehicle(c.award.vehicle)).put("next", c.award.nextCounter).put("guaranteed", c.award.guaranteed)
    private fun gacha(j: JSONObject): GachaCommand {
        if (j.getString("type") != "gacha-v1") throw JSONException("가챠 명령 종류 오류")
        return GachaCommand(RequestId(j.getString("request")), InputSource.valueOf(j.getString("source")),
            ContentVersion(j.getString("content")), RuleVersion(j.getString("rules")), Revision(int(j, "revision")),
            int(j, "shopLevel", 1), int(j, "capacity"), int(j, "counter"), GachaTicketUse.valueOf(j.getString("ticket")),
            GachaAward(LedgerJson.vehicle(j.getJSONObject("vehicle")), int(j, "next"), j.getBoolean("guaranteed")))
    }
    private fun int(j: JSONObject, key: String, min: Long = 0) = LedgerJson.integer(j, key, min, Int.MAX_VALUE.toLong()).toInt()
    private fun garage(j: JSONObject): GameChange {
        val v = j.getJSONObject("write")
        if (v.getString("type") != "update") throw JSONException("차량 관리 Update만 지원")
        val money = when (val raw = j.get("money")) {
            JSONObject.NULL -> null
            is JSONObject -> MoneyChange(MoneyDirection.valueOf(raw.getString("direction")),
                GameMoney(LedgerJson.integer(raw, "amount", 0, Long.MAX_VALUE)))
            else -> throw JSONException("금액 형식 오류")
        }
        return GameChange(RequestId(j.getString("request")), TransactionKind.valueOf(j.getString("kind")),
            InputSource.valueOf(j.getString("source")), RuleVersion(j.getString("rule")), money,
            VehicleWrite.Update(OwnedVehicleId(v.getString("id")), Revision(int(v,"revision")),
                int(v,"level",1), VehicleLifecycle.valueOf(v.getString("lifecycle")), int(v,"levelProgressPercent")))
    }
}
