/**
 * Precompiled [chat.ktlint.gradle.kts][Chat_ktlint_gradle] script plugin.
 *
 * @see Chat_ktlint_gradle
 */
public
class Chat_ktlintPlugin : org.gradle.api.Plugin<org.gradle.api.Project> {
    override fun apply(target: org.gradle.api.Project) {
        try {
            Class
                .forName("Chat_ktlint_gradle")
                .getDeclaredConstructor(org.gradle.api.Project::class.java, org.gradle.api.Project::class.java)
                .newInstance(target, target)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.targetException
        }
    }
}
