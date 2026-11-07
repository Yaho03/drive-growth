package io.drivegrowth.core.content

/** 테스트용 5종 콘텐츠. 해금 레벨은 PRD 배치가 아닌 테스트값이다. 실제 20종 표는 app/src/main/assets/content/game-content.csv. */
object GameContentFixtures {
    val facts = """
        vehicle_id,model_name
        hyundai_casper,캐스퍼
        hyundai_avante,아반떼
        hyundai_sonata,쏘나타
        hyundai_grandeur,그랜저
        genesis_g90,G90
    """.trimIndent()

    val content = """
        # content_version=fixture-content-v0
        # rule_version=fixture-rule-v0
        # trial=true
        # price_unit=8000
        # gacha_weights=COMMON:747,ADVANCED:200,RARE:50,LEGEND:3
        # gacha_pity=30
        # gacha_guaranteed_from=RARE
        vehicle_id,price_ratio_percent,unlock_shop_level
        hyundai_casper,100,1
        hyundai_avante,120,1
        hyundai_sonata,150,1
        hyundai_grandeur,200,1
        genesis_g90,300,2
    """.trimIndent()

    fun loaded(): GameContentSnapshot = (GameContentLoader.load(facts, content) as ContentLoad.Loaded).snapshot
}
