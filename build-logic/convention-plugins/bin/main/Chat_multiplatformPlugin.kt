/**
 * Precompiled [chat.multiplatform.gradle.kts][Chat_multiplatform_gradle] script plugin.
 *
 * @see Chat_multiplatform_gradle
 */
public
class Chat_multiplatformPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Chat_multiplatform_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
