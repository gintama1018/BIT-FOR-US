package com.meshwhisper.app.location

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.util.PlusCodeHelper
import org.junit.Test

class PlusCodeHelperTest {

    @Test
    fun encode_producesValidTenCharPlusCodeWithSeparatorAtPosition8() {
        val code = PlusCodeHelper.encode(12.971598, 77.594562)
        assertThat(code.length).isEqualTo(11) // 10 chars + '+' separator = 11
        assertThat(code[8]).isEqualTo('+')
        assertThat(code).startsWith("7J4V")
    }

    @Test
    fun encode_deterministicAndHandlesNegativeCoordinates() {
        val southWestCode = PlusCodeHelper.encode(-33.8688, 151.2093)
        assertThat(southWestCode.length).isEqualTo(11)
        assertThat(southWestCode[8]).isEqualTo('+')

        val negativeLonCode = PlusCodeHelper.encode(40.7128, -74.0060)
        assertThat(negativeLonCode.length).isEqualTo(11)
        assertThat(negativeLonCode[8]).isEqualTo('+')

        // Re-encoding same coordinate produces identical code
        val repeatCode = PlusCodeHelper.encode(40.7128, -74.0060)
        assertThat(repeatCode).isEqualTo(negativeLonCode)
    }

    @Test
    fun encode_handlesPolesAndBoundaryCoordinates() {
        val northPole = PlusCodeHelper.encode(90.0, 0.0)
        assertThat(northPole.length).isEqualTo(11)
        assertThat(northPole[8]).isEqualTo('+')

        val southPole = PlusCodeHelper.encode(-90.0, 0.0)
        assertThat(southPole.length).isEqualTo(11)
        assertThat(southPole[8]).isEqualTo('+')

        val dateLine = PlusCodeHelper.encode(0.0, 180.0)
        assertThat(dateLine.length).isEqualTo(11)
        assertThat(dateLine[8]).isEqualTo('+')
    }
}
