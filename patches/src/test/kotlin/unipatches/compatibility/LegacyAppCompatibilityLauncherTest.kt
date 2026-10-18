package unipatches.compatibility

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyAppCompatibilityLauncherTest {
    private val unityType = "Lorg/example/CustomUnityActivity;"
    private val hierarchy = mapOf(
        unityType to "Lcom/unity3d/player/UnityPlayerActivity;",
        "Lcom/unity3d/player/UnityPlayerActivity;" to "Landroid/app/Activity;",
    )

    private fun launchInstructions(target: String, intentRegister: Int = 0) = listOf(
        BuilderInstruction21c(Opcode.NEW_INSTANCE, intentRegister, ImmutableTypeReference("Landroid/content/Intent;")),
        BuilderInstruction21c(Opcode.CONST_CLASS, 2, ImmutableTypeReference(target)),
        BuilderInstruction35c(
            Opcode.INVOKE_DIRECT, 3, intentRegister, 1, 2, 0, 0,
            ImmutableMethodReference(
                "Landroid/content/Intent;", "<init>",
                listOf("Landroid/content/Context;", "Ljava/lang/Class;"), "V",
            ),
        ),
        BuilderInstruction35c(
            Opcode.INVOKE_VIRTUAL, 2, 1, intentRegister, 0, 0, 0,
            ImmutableMethodReference("Landroid/app/Activity;", "startActivity", listOf("Landroid/content/Intent;"), "V"),
        ),
    )

    @Test
    fun `detects arbitrary subclass launched by standard downloader`() {
        val instructions = launchInstructions(unityType)
        assertEquals(listOf(0, 1, 2), intentConstructorRegisters(instructions[2]))
        assertEquals(unityType, constructedActivityType(instructions, 2, 2))
        assertTrue(isUnityActivityClass(unityType, hierarchy))
        assertEquals(setOf(unityType), unityActivityLaunchTargets(instructions, hierarchy))
        assertTrue(isUnityActivityClass(unityType, hierarchy))
    }

    @Test
    fun `recognizes Unity activity subclasses without enumerating base class names`() {
        val customBase = "Lcom/unity3d/player/FuturePlayerActivity;"
        val custom = "Lorg/example/FutureUnityActivity;"
        assertTrue(isUnityActivityClass(custom, mapOf(custom to customBase, customBase to "Landroid/app/Activity;")))
        assertFalse(isUnityActivityClass(custom, mapOf(custom to "Lcom/unity3d/player/UnityPlayer;", "Lcom/unity3d/player/UnityPlayer;" to "Ljava/lang/Object;")))
    }

    @Test
    fun `requires direct intent launch adjacency`() {
        val instructions = launchInstructions(unityType).toMutableList().apply {
            add(3, BuilderInstruction10x(Opcode.NOP))
        }
        assertTrue(unityActivityLaunchTargets(instructions, hierarchy).isEmpty())
    }

    @Test
    fun `rejects non-unity and non-started activities`() {
        val ordinaryActivity = "Lorg/example/OrdinaryActivity;"
        assertTrue(unityActivityLaunchTargets(launchInstructions(ordinaryActivity), hierarchy).isEmpty())
        val wrongIntent = launchInstructions(unityType).toMutableList().apply {
            this[3] = BuilderInstruction35c(
                Opcode.INVOKE_VIRTUAL, 2, 1, 9, 0, 0, 0,
                ImmutableMethodReference("Landroid/app/Activity;", "startActivity", listOf("Landroid/content/Intent;"), "V"),
            )
        }
        assertTrue(unityActivityLaunchTargets(wrongIntent, hierarchy).isEmpty())
    }
}
