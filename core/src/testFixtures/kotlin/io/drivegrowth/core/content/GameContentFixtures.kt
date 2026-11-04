package io.drivegrowth.core.content

/** 시험용 콘텐츠. 가격·해금 레벨은 임의 시험값이며 PRD의 게임용 가격표가 아니다. 가챠 수치는 PRD 시험값. */
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
        # gacha_weights=COMMON:747,ADVANCED:200,RARE:50,LEGEND:3
        # gacha_pity=30
        # gacha_guaranteed_from=RARE
        vehicle_id,game_price,rarity,unlock_shop_level
        hyundai_casper,8000,COMMON,1
        hyundai_avante,10000,COMMON,1
        hyundai_sonata,15000,ADVANCED,1
        hyundai_grandeur,25000,RARE,1
        genesis_g90,60000,LEGEND,2
    """.trimIndent()

    fun loaded(): GameContentSnapshot = (GameContentLoader.load(facts, content) as ContentLoad.Loaded).snapshot
}
