plugins {
    kotlin("jvm")
    `java-test-fixtures`
}
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { testLogging { events("passed", "skipped", "failed") } }
