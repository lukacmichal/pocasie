package sk.lukac.pocasie

import org.junit.Assert.assertEquals
import org.junit.Test

class VelkostWidgetuTest {

    // Widget 4×3 bunky: na vysku 330×290 dp, na sirku 600×150 dp.

    @Test
    fun `na vysku uzsi a vyssi`() {
        assertEquals(330, VelkostWidgetu.sirkaDp(330, 600, naVysku = true))
        assertEquals(290, VelkostWidgetu.vyskaDp(150, 290, naVysku = true))
    }

    @Test
    fun `na sirku sirsi a nizsi`() {
        assertEquals(600, VelkostWidgetu.sirkaDp(330, 600, naVysku = false))
        assertEquals(150, VelkostWidgetu.vyskaDp(150, 290, naVysku = false))
    }

    @Test
    fun `chybajuca hodnota sa nahradi druhou`() {
        assertEquals(150, VelkostWidgetu.vyskaDp(150, 0, naVysku = true))
        assertEquals(330, VelkostWidgetu.sirkaDp(330, 0, naVysku = false))
    }

    @Test
    fun `launcher nic neposlal`() {
        assertEquals(0, VelkostWidgetu.vyskaDp(0, 0, naVysku = true))
        assertEquals(0, VelkostWidgetu.sirkaDp(0, 0, naVysku = false))
    }
}
