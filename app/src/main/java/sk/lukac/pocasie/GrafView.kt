package sk.lukac.pocasie

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Graf pocasia v appke: stlpce zrazok a ciara teploty nad casovou osou.
 *
 * Kresli [GrafKresba] — to iste, cim sa kresli obrazok do widgetu. Tu ostava
 * len to, co widget mat nemoze: **tahanie prstom vybera stlpec** (rovnako ako
 * graf v askener-i). Cislo jednej hodiny sa do stlpca sirokeho tri pixely
 * nakreslit neda, ale nad grafom je na neho riadok. Prst na taky stlpec
 * netrafi, preto sa hlada NAJBLIZSI stlpec na osi x, nie zasah.
 *
 * Do 3. 9. 2026 sa trieda volala `ZrazkyGrafView` a teplotu nekreslila.
 */
class GrafView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var stlpce: List<Graf.Stlpec> = emptyList()
    private var vybrany: Int = -1

    /** Zavola sa pri tahani prstom; null = prst odisiel. */
    var naVyber: ((Graf.Stlpec?) -> Unit)? = null

    private val farby = GrafKresba.Farby(
        dazd = ContextCompat.getColor(context, R.color.zrazky),
        sneh = ContextCompat.getColor(context, R.color.text_vedlajsi),
        teplota = ContextCompat.getColor(context, R.color.teplo),
        ciara = ContextCompat.getColor(context, R.color.ciara),
        text = ContextCompat.getColor(context, R.color.text_vedlajsi),
        vyber = ContextCompat.getColor(context, R.color.text_hlavny),
    )

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /**
     * Cez `applyDimension`, nie cez `scaledDensity`: takto sa zvacsene pismo
     * z Nastaveni (ktore [Vzhlad] vlozi do kontextu) prejavi aj v grafe.
     */
    private fun sp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    fun nastav(nove: List<Graf.Stlpec>) {
        stlpce = nove
        vybrany = -1
        invalidate()
    }

    // --------------------------- vyber prstom -------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (stlpce.isEmpty()) return false
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                // Graf je vnutri ScrollView — bez tohto by zvisly posun
                // ukradol tah hned po prvom pohybe prsta.
                parent?.requestDisallowInterceptTouchEvent(true)
                val vlavo = GrafKresba.odsadenieVlavo(stlpce, hustota(), sp(10f))
                val vpravo = width - GrafKresba.odsadenieVpravo(stlpce, hustota(), sp(10f))
                val vnutro = (vpravo - vlavo).coerceAtLeast(1f)
                val podiel = ((e.x - vlavo) / vnutro).coerceIn(0f, 1f)
                vybrany = Math.round(podiel * (stlpce.size - 1))
                naVyber?.invoke(stlpce[vybrany])
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                vybrany = -1
                naVyber?.invoke(null)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun hustota() = resources.displayMetrics.density

    // ---------------------------- kreslenie ---------------------------------

    override fun onDraw(canvas: Canvas) {
        if (stlpce.isEmpty()) return
        GrafKresba.nakresli(
            c = canvas,
            sirka = width.toFloat(),
            vyska = height.toFloat(),
            stlpce = stlpce,
            farby = farby,
            hustota = hustota(),
            velkostTextu = sp(10f),
            vybrany = vybrany,
            // Kolko hodin sa napise, rata GrafKresba podla sirky stlpca
            // (Graf.krokHodin): 24 h po dvoch, tyzden po dvanast. Do 12. 9. 2026
            // tu bol pevny strop 80 stlpcov a nad nim sa hodiny nepisali vobec.
            popisyHodin = stlpce.firstOrNull()?.jeHodinovy == true,
            suchoText = context.getString(R.string.graf_sucho),
        )
    }
}
