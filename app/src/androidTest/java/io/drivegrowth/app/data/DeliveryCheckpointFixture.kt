package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase

/** schema12에서 만든 시험 DB를 실제 구버전 fixture 구조로 되돌린다. 제품 이전/복구 API가 아니다. */
internal fun stripDeliveryCheckpointFixture(db:SQLiteDatabase) {
    stripRentalProductFixture(db)
    for(table in listOf("observed_driving_marker","observed_driving_state","observed_driving_receipts"))db.execSQL("DROP TABLE IF EXISTS $table")
    for(table in listOf("delivery_requests","delivery_changes","delivery_outcomes","delivery_hold_outcomes")) {
        val columns=db.rawQuery("PRAGMA table_info($table)",null).use{r->buildList{while(r.moveToNext())add(r.getString(1))}}
        if("host_command" !in columns)continue
        val sql=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst();it.getString(0)}
        val old=sql.replace(Regex(",\\s*host_command\\s+TEXT",RegexOption.IGNORE_CASE),"")
        check(old!=sql)
        db.execSQL("ALTER TABLE $table RENAME TO ${table}_schema12_fixture")
        db.execSQL(old)
        val names=columns.filter{it!="host_command"}.joinToString(",")
        db.execSQL("INSERT INTO $table ($names) SELECT $names FROM ${table}_schema12_fixture")
        db.execSQL("DROP TABLE ${table}_schema12_fixture")
    }
}

/** schema14 metadata/열만 제거해 이전 helper의 실제 구조를 만든다. 기존 방문 세 열과 행을 보존한다. */
internal fun stripRentalProductFixture(db:SQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS facility_command_results")
    for(table in listOf("rental_product_marker","rental_product_state","rental_product_inactive"))db.execSQL("DROP TABLE IF EXISTS $table")
    val columns=db.rawQuery("PRAGMA table_info(visits)",null).use{r->buildList{while(r.moveToNext())add(r.getString(1))}}
    if("host_command" !in columns)return
    db.execSQL("ALTER TABLE visits RENAME TO visits_schema14_fixture")
    db.execSQL("CREATE TABLE visits (id TEXT PRIMARY KEY NOT NULL, command TEXT NOT NULL, assigned TEXT)")
    db.execSQL("INSERT INTO visits(id,command,assigned) SELECT id,command,assigned FROM visits_schema14_fixture")
    db.execSQL("DROP TABLE visits_schema14_fixture")
}
