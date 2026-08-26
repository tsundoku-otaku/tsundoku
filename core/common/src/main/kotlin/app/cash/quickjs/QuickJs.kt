package app.cash.quickjs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import com.dokar.quickjs.QuickJs as DokarQuickJs

class QuickJs private constructor(private val delegate: DokarQuickJs) : Closeable {

    fun evaluate(script: String): Any? = runBlocking(Dispatchers.Default) {
        delegate.evaluate<Any?>(script)
    }

    fun evaluate(script: String, fileName: String): Any? = runBlocking(Dispatchers.Default) {
        delegate.evaluate<Any?>(script, fileName)
    }

    fun compile(sourceCode: String, fileName: String): ByteArray = delegate.compile(sourceCode, fileName)

    fun execute(bytecode: ByteArray): Any? = runBlocking(Dispatchers.Default) {
        delegate.evaluate<Any?>(bytecode)
    }

    override fun close() = delegate.close()

    companion object {
        @JvmStatic
        fun create(): QuickJs = QuickJs(DokarQuickJs.create(Dispatchers.Default))
    }
}
