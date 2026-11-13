import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    testImplementation(libs.junit)
}

// Real-data tests read a folder of DICOM files in place (never copied into the repo):
//   ./gradlew :dicom:test -PDICOM_TEST_DIR=$HOME/dicom/lidc_idri   (or env DICOM_TEST_DIR)
val dicomTestDir = providers.gradleProperty("DICOM_TEST_DIR")
    .orElse(providers.environmentVariable("DICOM_TEST_DIR"))
    .orElse("")

tasks.test {
    systemProperty("dicom.test.dir", dicomTestDir.get())
    inputs.property("dicomTestDir", dicomTestDir)
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
