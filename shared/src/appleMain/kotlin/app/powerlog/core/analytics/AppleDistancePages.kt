@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.powerlog.core.analytics

import kotlinx.cinterop.*
import platform.Foundation.*

private class AppleNumbers(val data: NSData) : DistanceNumbers {
    override val size = (data.length / 8uL).toInt()
    private val pointer = data.bytes?.reinterpret<DoubleVar>()

    override fun get(index: Int): Double {
        require(index in 0 until size)
        return pointer!![index]
    }

    override fun set(index: Int, value: Double) {
        require(index in 0 until size)
        pointer!![index] = value
    }
}

private class AppleKeys(val data: NSData) : DistanceKeys {
    override val size = (data.length / 8uL).toInt()
    private val pointer = data.bytes?.reinterpret<LongVar>()

    override fun get(index: Int): Long {
        require(index in 0 until size)
        return pointer!![index]
    }

    override fun set(index: Int, value: Long) {
        require(index in 0 until size)
        pointer!![index] = value
    }
}

class AppleDistancePageProjection internal constructor(page: DistancePageProjection) {
    val numbers: NSMutableData =
        ((page.numberBuffer as AppleNumbers).data as NSMutableData).also {
            it.setLength((page.count * 9 * 8).convert())
        }
    val keys: NSMutableData =
        ((page.keyBuffer as AppleKeys).data as NSMutableData).also {
            it.setLength((page.count * 2 * 8).convert())
        }
    val text: List<String> = page.text
    val totalQ: Long = page.totalQ
    val coveredUs: Long = page.coveredUs
}

object AppleDistancePages {
    var pageEntryObserverForTesting: ((String, Int) -> Unit)? = null

    private fun page(numbers: NSData, keys: NSData, text: List<String>): DistanceInputPage {
        require(numbers.length % 8uL == 0uL && numbers.length <= 128uL * 9uL * 8uL)
        require(keys.length % 8uL == 0uL && keys.length <= 128uL * 6uL * 8uL)
        return DistanceInputPage(AppleNumbers(numbers), AppleKeys(keys), text)
    }

    private fun collector(totalQ: Long, coveredUs: Long) =
        DistancePageCollector(
            totalQ,
            coveredUs,
            AppleNumbers(NSMutableData.create(length = (129 * 9 * 8).convert())!!),
            AppleKeys(NSMutableData.create(length = (129 * 2 * 8).convert())!!),
        )

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun controller(
        kernel: ControllerDistanceKernel,
        numbers: NSMutableData,
        keys: NSMutableData,
        text: List<String>,
        totalQ: Long,
        coveredUs: Long,
    ): AppleDistancePageProjection {
        val input = page(numbers, keys, text)
        pageEntryObserverForTesting?.invoke("controller", input.count(4, 6, 6))
        return AppleDistancePageProjection(DistancePages.controller(kernel, input, collector(totalQ, coveredUs)))
    }

    @Throws(IllegalArgumentException::class, ArithmeticException::class)
    fun gps(
        kernel: GPSKernel,
        numbers: NSMutableData,
        keys: NSMutableData,
        text: List<String>,
        totalQ: Long,
        coveredUs: Long,
    ): AppleDistancePageProjection {
        val input = page(numbers, keys, text)
        pageEntryObserverForTesting?.invoke("gps", input.count(9, 5, 5))
        return AppleDistancePageProjection(DistancePages.gps(kernel, input, collector(totalQ, coveredUs)))
    }
}
