/*
 * Copyright (c) 2026 DNA Mobile Applications.
 * All rights reserved.
 *
 * This file is DroidBridge project code.
 * It is not part of Minecraft and does not grant rights to Minecraft,
 * Mojang, Microsoft, PojavLauncher, Zalith Launcher, or any third-party project.
 *
 * Files written entirely by DNA Mobile Applications are proprietary unless
 * a file header or separate license notice states otherwise.
 */

package ca.dnamobile.javalauncher.auth;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.UUID;

/**
 * OfflineAccountManager handles offline account operations including
 * account creation, activation, and session management for offline mode.
 */
public final class OfflineAccountManager {
    private static final String PREFS_OFFLINE_ACCOUNTS = "offline_accounts";
    private static final String KEY_ACTIVE_OFFLINE_ID = "active_offline_account_id";
    private static final String KEY_LAST_LOGIN_TIME = "last_offline_login_time";
    private static final String KEY_LOGIN_COUNT = "offline_login_count";

    private final Context context;
    private final SharedPreferences preferences;

    public OfflineAccountManager(@NonNull Context context) {
        this.context = context;
        this.preferences = context.getSharedPreferences(PREFS_OFFLINE_ACCOUNTS, Context.MODE_PRIVATE);
    }

    /**
     * Creates a new offline account session.
     *
     * @param accountId the unique identifier for the account
     * @return true if session was created successfully
     */
    public boolean createOfflineSession(@NonNull String accountId) {
        try {
            long currentTime = System.currentTimeMillis();
            preferences.edit()
                    .putString(KEY_ACTIVE_OFFLINE_ID, accountId)
                    .putLong(KEY_LAST_LOGIN_TIME, currentTime)
                    .putInt(KEY_LOGIN_COUNT, getLoginCount(accountId) + 1)
                    .apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Retrieves the currently active offline account ID.
     *
     * @return the active offline account ID, or null if none active
     */
    @Nullable
    public String getActiveOfflineAccountId() {
        return preferences.getString(KEY_ACTIVE_OFFLINE_ID, null);
    }

    /**
     * Gets the last login time for an offline account.
     *
     * @return milliseconds since epoch of last login
     */
    public long getLastLoginTime() {
        return preferences.getLong(KEY_LAST_LOGIN_TIME, 0);
    }

    /**
     * Gets the number of times an offline account has been logged into.
     *
     * @param accountId the account ID
     * @return login count
     */
    public int getLoginCount(@NonNull String accountId) {
        return preferences.getInt("login_count_" + accountId, 0);
    }

    /**
     * Clears the active offline account session.
     */
    public void clearActiveSession() {
        preferences.edit()
                .remove(KEY_ACTIVE_OFFLINE_ID)
                .remove(KEY_LAST_LOGIN_TIME)
                .apply();
    }

    /**
     * Checks if an offline account session is valid.
     *
     * @return true if an offline account is currently active
     */
    public boolean hasActiveSession() {
        return getActiveOfflineAccountId() != null;
    }

    /**
     * Generates a unique session token for offline play.
     *
     * @return a UUID-based session token
     */
    @NonNull
    public String generateSessionToken() {
        return "offline-" + UUID.randomUUID().toString();
    }
}
