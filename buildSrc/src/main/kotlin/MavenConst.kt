import org.gradle.api.Project
import org.gradle.api.artifacts.dsl.RepositoryHandler
import java.io.File
import java.net.URI

/*
 * Maven 发布用的常量
 *
 * @author Ysj
 * Create time: 2023/6/30
 */

val Project.MAVEN_LOCAL: URI
    get() = File(rootDir, "repos").toURI()

const val PROJECT_GROUP = "com.play.ai"
const val PROJECT_VERSION = "1.0.0-SNAPSHOT"

const val POM_DEVELOPER_ID = "Yizhiyang"
const val POM_DEVELOPER_NAME = "Yizhiyang"

fun Project.applyMavenLocal(handler: RepositoryHandler) = handler.maven {
    url = MAVEN_LOCAL
}