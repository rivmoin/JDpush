package com.example.qlcookie

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.app.Activity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var etAddress: EditText
    private lateinit var etClientId: EditText
    private lateinit var etClientSecret: EditText
    private lateinit var etCookie: EditText
    private lateinit var etRemarks: EditText
    private lateinit var btnPush: Button
    private lateinit var tvResult: TextView
    private lateinit var progress: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeEdit(hint: String, single: Boolean = true): EditText =
        EditText(this).apply {
            this.hint = hint
            setSingleLine(single)
            if (!single) {
                minLines = 4
                gravity = Gravity.TOP
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 12f
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(6), 0, 0) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("qlcfg", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "🍪 京东 Cookie → 青龙 一键推送"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(title)

        etAddress = makeEdit("青龙地址，如 http://1.2.3.4:5700")
        etClientId = makeEdit("client_id")
        etClientSecret = makeEdit("client_secret").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        etCookie = makeEdit("在此粘贴完整 JD_COOKIE（含 pt_key 与 pt_pin）", single = false)
        etRemarks = makeEdit("备注（可选），如：小号 |UID_sdxxxx")

        root.addView(etAddress); root.addView(etClientId); root.addView(etClientSecret)
        root.addView(etCookie); root.addView(etRemarks)

        btnPush = Button(this).apply { text = "推送到青龙" }
        root.addView(btnPush)

        progress = ProgressBar(this).apply { visibility = View.GONE }
        root.addView(progress)

        tvResult = TextView(this).apply {
            textSize = 14f
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(tvResult)

        etAddress.setText(sp.getString("address", ""))
        etClientId.setText(sp.getString("client_id", ""))
        etClientSecret.setText(sp.getString("client_secret", ""))

        btnPush.setOnClickListener { push() }
    }

    private fun push() {
        val address = etAddress.text.toString().trim().trimEnd('/')
        val cid = etClientId.text.toString().trim()
        val secret = etClientSecret.text.toString().trim()
        val cookie = etCookie.text.toString().trim()
        val remarks = etRemarks.text.toString().trim()

        if (address.isEmpty() || cid.isEmpty() || secret.isEmpty()) {
            toast("请先填写青龙地址 / client_id / client_secret"); return
        }
        if (!cookie.contains("pt_key=") || !cookie.contains("pt_pin=")) {
            toast("Cookie 中未找到 pt_key / pt_pin，请检查"); return
        }

        getSharedPreferences("qlcfg", Context.MODE_PRIVATE).edit()
            .putString("address", address).putString("client_id", cid)
            .putString("client_secret", secret).apply()

        btnPush.isEnabled = false; progress.visibility = View.VISIBLE
        tvResult.text = ""; tvResult.setTextColor(Color.parseColor("#333333"))

        thread {
            val (ok, msg) = try {
                doPush(address, cid, secret, cookie, remarks)
            } catch (e: Exception) {
                false to "出错：${e.message}"
            }
            runOnUiThread {
                btnPush.isEnabled = true; progress.visibility = View.GONE
                tvResult.text = msg
                tvResult.setTextColor(
                    if (ok) Color.parseColor("#065F46") else Color.parseColor("#991B1B")
                )
            }
        }
    }

    private fun doPush(address: String, cid: String, secret: String,
                       cookie: String, remarks: String): Pair<Boolean, String> {
        // 1. 登录
        val qs = "client_id=${enc(cid)}&client_secret=${enc(secret)}"
        val login = ql(address, "/open/auth/token?$qs", null, "GET", null)
        if (login.getInt("code") != 200)
            return false to "青龙登录失败：${login.optString("message")}"
        val d = login.getJSONObject("data")
        val auth = "${d.getString("token_type")} ${d.getString("token")}"

        // 2. 解析 pt_pin
        val m = Regex("pt_pin=([^;]+)").find(cookie)
            ?: return false to "从 Cookie 解析 pt_pin 失败"
        val pin = java.net.URLDecoder.decode(m.groupValues[1], "UTF-8")

        // 3. 查已有 JD_COOKIE
        val envs = ql(address, "/open/envs?searchValue=JD_COOKIE", auth, "GET", null)
        if (envs.getInt("code") != 200)
            return false to "获取环境变量失败：${envs.optString("message")}"
        var targetId: Long? = null
        var oldRemarks = ""
        val arr: JSONArray = envs.optJSONArray("data") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val v = java.net.URLDecoder.decode(item.optString("value"), "UTF-8")
            if (v.contains("pt_pin=$pin")) {
                targetId = item.getLong("id"); oldRemarks = item.optString("remarks"); break
            }
        }

        // 4. 更新 or 新增
        return if (targetId != null) {
            val body = JSONObject()
                .put("value", cookie).put("name", "JD_COOKIE")
                .put("remarks", if (remarks.isEmpty()) oldRemarks else remarks)
                .put("id", targetId)
            val r = ql(address, "/open/envs", auth, "PUT", body.toString())
            if (r.getInt("code") == 200) true to "✅ 已更新账号【$pin】的 JD_COOKIE"
            else false to "更新失败：${r.optString("message")}"
        } else {
            val body = JSONArray().put(JSONObject()
                .put("value", cookie).put("name", "JD_COOKIE").put("remarks", remarks))
            val r = ql(address, "/open/envs", auth, "POST", body.toString())
            if (r.getInt("code") == 200) true to "✅ 已新增账号【$pin】的 JD_COOKIE"
            else false to "新增失败：${r.optString("message")}"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun ql(address: String, path: String, auth: String?,
                   method: String, body: String?): JSONObject {
        val conn = URL(address + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000; conn.readTimeout = 15000
        conn.setRequestProperty("Content-Type", "application/json")
        auth?.let { conn.setRequestProperty("Authorization", it) }
        if (body != null) {
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, "UTF-8").use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
        conn.disconnect()
        return JSONObject(text)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
