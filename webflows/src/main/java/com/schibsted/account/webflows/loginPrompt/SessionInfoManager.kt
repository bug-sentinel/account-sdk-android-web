package com.schibsted.account.webflows.loginPrompt

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build


internal class SessionInfoManager(context: Context) {
    private val contentResolver = context.contentResolver;
    private val packageName = context.packageName
    private val packageManager = context.packageManager

    fun save() {
        contentResolver.insert(
            Uri.parse("content://${packageName}.contentprovider/sessions"),
            ContentValues().apply {
                put("packageName", packageName)
            })
    }

    fun clear() {
        contentResolver.delete(
            Uri.parse("content://${packageName}.contentprovider/sessions"),
            null,
            arrayOf(packageName)
        )
    }

    private fun isSessionPresent(authority: String): Boolean {
        val cursor =
            contentResolver.query(
                Uri.parse("content://${authority}/sessions"),
                null,
                null,
                null,
                null
            )
        return cursor?.count != null && cursor.count > 0
    }

    suspend fun isUserLoggedInOnTheDevice(context: Context): Boolean {
        var contentProviders: List<ResolveInfo>;
        val intent = Intent("com.schibsted.account.LOGIN_PROMPT_CONTENT_PROVIDER")
        contentProviders = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> packageManager.queryIntentContentProviders(
                intent,
                PackageManager.ResolveInfoFlags.of(0)
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> packageManager.queryIntentContentProviders(
                intent,
                PackageManager.MATCH_ALL
            )

            else -> packageManager.queryIntentContentProviders(intent, 0)
        }
        for (contentProvider in contentProviders) {
            if (isSessionPresent(contentProvider.providerInfo.authority)) {
                return true
            }
        }
        return false
    }
}