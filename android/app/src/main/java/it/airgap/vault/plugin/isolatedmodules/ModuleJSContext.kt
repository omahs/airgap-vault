package it.airgap.vault.plugin.isolatedmodules

import android.content.Context
import androidx.javascriptengine.JavaScriptIsolate
import androidx.javascriptengine.JavaScriptSandbox
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import it.airgap.vault.util.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.asDeferred
import kotlinx.coroutines.withContext
import java.util.*

class ModuleJSContext(private val context: Context) {
    private val jsAsyncResult: JSAsyncResult = JSAsyncResult()

    private val webViewLoadedDeferred: CompletableDeferred<Unit> = CompletableDeferred()

    private val modules: MutableMap<String, String> = mutableMapOf()

    suspend fun evaluateLoadModules(protocolType: JSProtocolType?): JSObject =
        evaluate(JSProtocolAction.LoadModules(protocolType))

    suspend fun evaluateCallOfflineProtocolMethod(
        name: String,
        args: JSArray?,
        protocolIdentifier: String,
    ): JSObject = evaluate(JSProtocolAction.CallOfflineProtocolMethod(name, args, protocolIdentifier))

    suspend fun evaluateCallOnlineProtocolMethod(
        name: String,
        args: JSArray?,
        protocolIdentifier: String,
        networkId: String?,
    ): JSObject = evaluate(JSProtocolAction.CallOnlineProtocolMethod(name, args, protocolIdentifier, networkId))

    suspend fun evaluateCallBlockExplorerMethod(
        name: String,
        args: JSArray?,
        protocolIdentifier: String,
        networkId: String?,
    ): JSObject = evaluate(JSProtocolAction.CallBlockExplorerMethod(name, args, protocolIdentifier, networkId))

    suspend fun evaluateCallV3SerializerCompanionMethod(
        name: String,
        args: JSArray?,
        moduleIdentifier: String,
    ): JSObject = evaluate(JSProtocolAction.CallV3SerializerCompanionMethod(name, args, moduleIdentifier))


    suspend fun destroy() {
        jsIsolates.values.forEach { it.close() }
        jsSandbox.await().close()
    }

    private val jsSandbox: Deferred<JavaScriptSandbox> = JavaScriptSandbox.createConnectedInstanceAsync(context).asDeferred()
    private val jsIsolates: MutableMap<String, JavaScriptIsolate> = mutableMapOf()

    @Throws(JSException::class)
    private suspend fun evaluate(action: JSProtocolAction): JSObject = withContext(Dispatchers.Default) {


        useIsolatedModule(identifier) { jsIsolate, module ->
            val script = """
                execute(
                    global.${module},
                    '${identifier}',
                    ${options.orUndefined()},
                    $action,
                    function (result) {
                        return JSON.stringify({ ${action.resultField}: result });
                    },
                    function (error) {
                        return JSON.stringify({ error });
                    }
                );
            """.trimIndent()

            val result = jsIsolate.evaluateJavaScriptAsync(script).asDeferred().await()
            val jsObject = JSObject(result)
            jsObject.getString("error")?.let { error -> throw JSException(error) }

            jsObject
        }
    }

    private suspend inline fun <R> useIsolatedModule(identifier: String, block: (JavaScriptIsolate, String) -> R): R {
        val moduleName = moduleName(identifier) ?: failWithModuleNotFound(identifier)
        val jsIsolate = jsIsolates.getOrPut(moduleName) {
            jsSandbox.await().createIsolate().also {
                it.evaluateJavaScriptAsync("var global = {};")
                val isolatedProtocolScript = context.assets.open("public/assets/native/isolated_modules/isolated-protocol.script.js").use { stream -> stream.readBytes().decodeToString() }
                it.evaluateJavaScriptAsync(isolatedProtocolScript).asDeferred().await()
                it.loadModule(moduleName)
            }
        }

        return block(jsIsolate, createJSModule(moduleName))
    }

    private suspend fun JavaScriptIsolate.loadModule(name: String) {
        val source = readModuleSource(name)
        provideNamedData("${name}-script", source.encodeToByteArray())
        evaluateJavaScriptAsync("""
            function utf8ArrayToStr(array) {
              var out, i, len, c;
              var char2, char3;

              out = "";
              len = array.length;
              i = 0;
              while (i < len) {
                c = array[i++];
                switch (c >> 4)
                { 
                  case 0: case 1: case 2: case 3: case 4: case 5: case 6: case 7:
                    // 0xxxxxxx
                    out += String.fromCharCode(c);
                    break;
                  case 12: case 13:
                    // 110x xxxx   10xx xxxx
                    char2 = array[i++];
                    out += String.fromCharCode(((c & 0x1F) << 6) | (char2 & 0x3F));
                    break;
                  case 14:
                    // 1110 xxxx  10xx xxxx  10xx xxxx
                    char2 = array[i++];
                    char3 = array[i++];
                    out += String.fromCharCode(((c & 0x0F) << 12) |
                                               ((char2 & 0x3F) << 6) |
                                               ((char3 & 0x3F) << 0));
                    break;
                }
              }    
              return out;
            }
        """.trimIndent())
        evaluateJavaScriptAsync("""
            android.consumeNamedDataAsArrayBuffer('${name}-script').then((value) => {
                var string = utf8ArrayToStr(new Uint8Array(value));
                eval(string);
            });
        """.trimIndent()).asDeferred().await()
    }

    private fun JSObject?.orUndefined(): Any = this ?: JSUndefined

    private fun JSArray.replaceNullWithUndefined(): JSArray =
        JSArray(toList<Any>().map { if (it == JSObject.NULL) JSUndefined else it })

    private fun readModuleSource(name: String): String =
        context.assets.open("public/assets/libs/airgap-${name}.browserify.js").use { it.readBytes().decodeToString() }

    private fun createJSModule(name: String): String =
        "airgapCoinLib${name.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }}"

    private fun moduleName(identifier: String): String? =
        when {
            identifier.startsWithAny("ae") -> "aeternity"
            identifier.startsWithAny("astar", "shiden") -> "astar"
            identifier.startsWithAny("btc") -> "bitcoin"
            identifier.startsWithAny("cosmos") -> "cosmos"
            identifier.startsWithAny("eth") -> "ethereum"
            identifier.startsWithAny("grs") -> "groestlcoin"
            identifier.startsWithAny("moonbeam", "moonriver", "moonbase") -> "moonbeam"
            identifier.startsWithAny("polkadot", "kusama") -> "polkadot"
            identifier.startsWithAny("xtz") -> "tezos"
            else -> null
        }

    enum class JSProtocolType {
        Offline, Online, Full;

        override fun toString(): String = name.lowercase()

        companion object {
            fun fromString(value: String): JSProtocolType? = values().find { it.name.lowercase() == value.lowercase() }
        }
    }

    enum class JSCallMethodTarget {
        Offline, Online, BlockExplorer, V3SerializerCompanion;

        override fun toString(): String = name.lowercase()

        companion object {
            fun fromString(value: String): JSCallMethodTarget? = values().find { it.name.lowercase() == value.lowercase() }
        }
    }

    private sealed interface JSProtocolAction {
        val resultField: String
            get() = "result"

        data class LoadModules(val protocolType: JSProtocolType?) : JSProtocolAction {
            override val resultField: String = "loadModules"

            override fun toString(): String = JSObject("""
                {
                    "type": "$TYPE",
                    "protocolType": ${protocolType?.toString().toJson()}
                }
            """.trimIndent()).toString()

            companion object {
                private const val TYPE = "loadModules"
            }
        }

        sealed class CallMethod(val target: JSCallMethodTarget, private val partial: JSObject) : JSProtocolAction {
            abstract val name: String
            abstract val args: JSArray?

            override fun toString(): String {
                val args = args?.toString() ?: "[]"

                return JSObject("""
                    {
                        "type": "$TYPE",
                        "target": "$target",
                        "method": "$name",
                        "args": $args
                    }
                """.trimIndent())
                    .assign(partial)
                    .toString()
            }

            companion object {
                private const val TYPE = "callMethod"
            }
        }

        data class CallOfflineProtocolMethod(
            override val name: String,
            override val args: JSArray?,
            val protocolIdentifier: String,
        ) : CallMethod(JSCallMethodTarget.Offline, JSObject("""
            {
                protocolIdentifier: "$protocolIdentifier"
            }
        """.trimIndent()))

        data class CallOnlineProtocolMethod(
            override val name: String,
            override val args: JSArray?,
            val protocolIdentifier: String,
            val networkId: String?,
        ) : CallMethod(JSCallMethodTarget.Online, JSObject("""
            {
                protocolIdentifier: "$protocolIdentifier",
                networkId: ${networkId.toJson()}
            }
        """.trimIndent()))

        data class CallBlockExplorerMethod(
            override val name: String,
            override val args: JSArray?,
            val protocolIdentifier: String,
            val networkId: String?,
        ) : CallMethod(JSCallMethodTarget.BlockExplorer, JSObject("""
            {
                protocolIdentifier: "$protocolIdentifier",
                networkId: ${networkId.toJson()}
            }
        """.trimIndent()))

        data class CallV3SerializerCompanionMethod(
            override val name: String,
            override val args: JSArray?,
            val moduleIdentifier: String,
        ) : CallMethod(JSCallMethodTarget.V3SerializerCompanion, JSObject("""
            {
                moduleIdentifier: "$moduleIdentifier"
            }
        """.trimIndent()))
    }

    @Throws(IllegalStateException::class)
    private fun failWithModuleNotFound(identifier: String): Nothing = throw IllegalStateException("Module $identifier could not be found.")
}