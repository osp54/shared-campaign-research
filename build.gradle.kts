import com.xpdustry.toxopid.extension.anukeJitpack
import com.xpdustry.toxopid.extension.anukeXpdustry
import com.xpdustry.toxopid.extension.anukeZelaux
import com.xpdustry.toxopid.spec.ModPlatform

plugins {
    java
    id("com.xpdustry.toxopid") version "4.2.0"
}

group = "com.ospx"
version = "0.2.5-alpha.1"

base {
    archivesName.set("shared-campaign-research")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
    anukeXpdustry()
    anukeZelaux()
    anukeJitpack()
}

toxopid {
    compileVersion = "v160"
    runtimeVersion = "v160"
    platforms = setOf(ModPlatform.DESKTOP, ModPlatform.ANDROID)
}

dependencies {
    compileOnly(toxopid.dependencies.arcCore)
    compileOnly(toxopid.dependencies.mindustryCore)

    testImplementation(toxopid.dependencies.arcCore)
    testImplementation(toxopid.dependencies.mindustryCore)
}

sourceSets {
    main {
        java.srcDir("src/main/java")
        resources.srcDir("src/main/resources")
        resources.srcDir("assets")
    }
}

tasks.processResources {
    from(rootProject.file("mod.hjson"))
    from(rootProject.file("bundles")) {
        into("bundles")
    }
}

tasks.withType<JavaCompile>().configureEach {
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("--release", "17"))
}
