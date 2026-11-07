import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}
allprojects {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
}
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    extensions.configure<BaseExtension>("android") {
        namespace = "britaflix.${project.name.lowercase()}"
        compileSdkVersion(35)
        defaultConfig { minSdk = 21; targetSdk = 35 }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "eagleblue2024-pixel/seriesflix-cloudstream")
        isCrossPlatform = true
        language = "es"
    }
    dependencies {
        add("implementation", "com.github.recloudstream.cloudstream:library:-SNAPSHOT")
        add("implementation", kotlin("stdlib"))
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.11")
        add("implementation", "org.jsoup:jsoup:1.18.3")
    }
}
