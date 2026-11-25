package com.voiceboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPolishPolicyTest {

    @Test
    fun longDashesBecomeShortHyphens() {
        assertEquals(
            "Москва - столица, а Питер - культурная столица",
            TextPolishPolicy.normalizeDashes("Москва — столица, а Питер – культурная столица")
        )
    }

    @Test
    fun ordinaryTextAndShortHyphensAreUntouched() {
        val text = "Что-то случилось, и я пошёл домой."
        assertEquals(text, TextPolishPolicy.normalizeDashes(text))
    }

    @Test
    fun commasAndTypoFixesCountAsALightEdit() {
        assertTrue(
            TextPolishPolicy.isLightEdit(
                "привет как дила что нового",
                "Привет, как дела, что нового?"
            )
        )
        assertTrue(
            TextPolishPolicy.isLightEdit(
                "Я пришол с работы и сел за стол",
                "Я пришёл с работы и сел за стол."
            )
        )
    }

    @Test
    fun addedShortDashesRemainALightEdit() {
        assertTrue(
            TextPolishPolicy.isLightEdit(
                "это был старый дом а это новый",
                "Это был старый дом, а это - новый."
            )
        )
    }

    @Test
    fun aParaphraseOrAnExtraSentenceIsRejected() {
        val original = "Сегодня я вышел из дома и пошёл в магазин за хлебом и молоком."
        val paraphrase = "Сегодня утром я покинул своё жилище и отправился в супермаркет, чтобы приобрести хлеб и молоко."
        val extraSentence =
            "Сегодня я вышел из дома и пошёл в магазин за хлебом и молоком. Погода была отличная."

        assertFalse(TextPolishPolicy.isLightEdit(original, paraphrase))
        assertFalse(TextPolishPolicy.isLightEdit(original, extraSentence))
    }
}
