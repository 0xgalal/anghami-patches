extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "app.anghami.extension"
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json classes are stubs in local JVM tests.
    testImplementation("org.json:json:20240303")
}
