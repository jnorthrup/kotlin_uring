
//    it.group = "com.vsiwest"
//    it.version = "1.0.2-SNAPSHOT"

rootProject.name = rootDir.name
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
//        includeBuild("buildsrc")
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
//    includeBuild("../somethingelse")
}


include(*(rootDir.listFiles()
.filter(File::isDirectory)
.filter { !it.isHidden }
.map(File::getName)-"buildSrc"-"gradle"-"build").toTypedArray())
