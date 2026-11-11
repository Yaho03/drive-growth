package io.drivegrowth.app.data

import io.drivegrowth.core.achievement.*
import io.drivegrowth.core.contract.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId

class TrialAchievementDefinitionsTest {
    private val text get() = sequenceOf(File("src/main/assets/content/trial-definitions.csv"),
        File("app/src/main/assets/content/trial-definitions.csv")).first { it.isFile }.readText()

    private fun parse(text: String): TrialDefinition {
        val ids = text.substringAfter("vehicle_id,hourly_rate\n").substringBefore("\n\n").lines()
            .map { VehicleId(it.substringBefore(',')) }.toSet()
        return (parseTrialDefinition(text, ids) as ContractResult.Success).value
    }

    @Test fun `네 지표의 시험 정의는 버전과 기존 Quest 여섯 개를 함께 보존한다`() {
        val parsed = parse(text)
        val definitions = (parsed.achievementDefinitions as ContractResult.Success).value
        assertEquals(AchievementMetric.entries.toSet(), definitions.keys)
        assertEquals(setOf(RuleVersion("achievement-trial-v1")), definitions.values.map { it.version }.toSet())
        assertTrue(definitions.values.all { it.trial })
        assertEquals(6, (parsed.questDefinitions(ZoneId.of("UTC")) as ContractResult.Success).value.definitions.size)
    }

    @Test fun `업적 정의 오류를 전달하면서 별도 Quest 공급은 읽을 수 있다`() {
        val base = text
        val old = "owned-vehicles,achievement-trial-v1,true,2,100"
        val missing = base.replace(Regex("\\[achievement_rewards]\\n[^\\[]+"), "")
        for ((candidate, expected) in listOf(
            missing to ContractError.DATA_UNAVAILABLE,
            base.replace(old, "owned-vehicles,achievement-trial-v1,true,2,0") to ContractError.INVALID_INPUT,
            base.replace(old, "$old\n$old") to ContractError.INVALID_INPUT,
            base.replace(old, "other,achievement-trial-v1,true,2,100") to ContractError.INVALID_INPUT,
        )) {
            val loaded = parse(candidate)
            assertEquals(ContractResult.Failure(expected), loaded.achievementDefinitions)
            assertEquals(6, (loaded.questDefinitions(ZoneId.of("UTC")) as ContractResult.Success).value.definitions.size)
        }
    }
}
