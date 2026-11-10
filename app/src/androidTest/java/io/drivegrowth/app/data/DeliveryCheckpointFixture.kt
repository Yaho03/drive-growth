package io.drivegrowth.app.data

import android.database.sqlite.SQLiteDatabase

/** schema12에서 만든 시험 DB를 실제 구버전 fixture 구조로 되돌린다. 제품 이전/복구 API가 아니다. */
internal fun stripDeliveryCheckpointFixture(db:SQLiteDatabase) {
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
