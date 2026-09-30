package app.gyro.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spoken prompts so the rider never has to look at the phone. Uses the navigation-guidance audio
 * usage, which ducks music the same way turn-by-turn directions do.
 */
class VoiceAnnouncer(context: Context) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val ids = AtomicInteger()

    @Volatile private var ready = false

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val russian = Locale.forLanguageTag("ru-RU")
        if (tts.isLanguageAvailable(russian) >= TextToSpeech.LANG_AVAILABLE) tts.language = russian
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        ready = true
    }

    /** Urgent prompts cut off whatever is being said; routine ones queue up. */
    fun speak(text: String, urgent: Boolean = false) {
        if (!ready || text.isBlank()) return
        tts.speak(text, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "gyro-${ids.incrementAndGet()}")
    }

    fun shutdown() {
        ready = false
        tts.stop()
        tts.shutdown()
    }

    companion object {
        /** Russian plural: 1 километр, 2 километра, 5 километров. */
        fun plural(n: Int, one: String, few: String, many: String): String {
            val mod100 = n % 100
            val mod10 = n % 10
            return when {
                mod100 in 11..14 -> many
                mod10 == 1 -> one
                mod10 in 2..4 -> few
                else -> many
            }
        }
    }
}
