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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * OfflineAccountSessionStore manages persistent storage of offline account sessions.
 * Tracks session history, playtime, and account activity.
 */
public final class OfflineAccountSessionStore {
    private static final String SESSION_STORE_DIR = "offline_sessions";
    private final Context context;
    private final File storageDir;

    public OfflineAccountSessionStore(@NonNull Context context) {
        this.context = context;
        this.storageDir = new File(context.getFilesDir(), SESSION_STORE_DIR);
        if (!storageDir.exists()) {
            storageDir.mkdirs();
        }
    }

    /**
     * Represents an offline account session.
     */
    public static class OfflineSession implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String sessionId;
        public final String accountId;
        public final long startTime;
        public long endTime;
        public long playtime; // in milliseconds
        public String gameVersion;
        public boolean successful;

        public OfflineSession(@NonNull String sessionId, @NonNull String accountId) {
            this.sessionId = sessionId;
            this.accountId = accountId;
            this.startTime = System.currentTimeMillis();
            this.endTime = 0;
            this.playtime = 0;
            this.gameVersion = "unknown";
            this.successful = false;
        }

        public void endSession() {
            this.endTime = System.currentTimeMillis();
            this.playtime = endTime - startTime;
        }

        @NonNull
        @Override
        public String toString() {
            return "OfflineSession{" +
                    "sessionId='" + sessionId + '\'' +
                    ", accountId='" + accountId + '\'' +
                    ", playtime=" + playtime +
                    ", gameVersion='" + gameVersion + '\'' +
                    ", successful=" + successful +
                    '}';
        }
    }

    /**
     * Saves an offline session to persistent storage.
     *
     * @param session the session to save
     * @return true if saved successfully
     */
    public boolean saveSession(@NonNull OfflineSession session) {
        try {
            File sessionFile = new File(storageDir, session.sessionId + ".session");
            try (FileOutputStream fos = new FileOutputStream(sessionFile);
                 ObjectOutputStream oos = new ObjectOutputStream(fos)) {
                oos.writeObject(session);
                oos.flush();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Loads a previously saved offline session.
     *
     * @param sessionId the session ID to load
     * @return the OfflineSession, or null if not found
     */
    @Nullable
    public OfflineSession loadSession(@NonNull String sessionId) {
        try {
            File sessionFile = new File(storageDir, sessionId + ".session");
            if (!sessionFile.exists()) {
                return null;
            }
            try (FileInputStream fis = new FileInputStream(sessionFile);
                 ObjectInputStream ois = new ObjectInputStream(fis)) {
                return (OfflineSession) ois.readObject();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets all sessions for a specific offline account.
     *
     * @param accountId the account ID
     * @return list of sessions
     */
    @NonNull
    public List<OfflineSession> getAccountSessions(@NonNull String accountId) {
        List<OfflineSession> sessions = new ArrayList<>();
        File[] files = storageDir.listFiles();
        if (files == null) {
            return sessions;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".session")) {
                String sessionId = file.getName().replace(".session", "");
                OfflineSession session = loadSession(sessionId);
                if (session != null && session.accountId.equals(accountId)) {
                    sessions.add(session);
                }
            }
        }
        return sessions;
    }

    /**
     * Calculates total playtime for an offline account.
     *
     * @param accountId the account ID
     * @return total playtime in milliseconds
     */
    public long getTotalPlaytime(@NonNull String accountId) {
        long total = 0;
        for (OfflineSession session : getAccountSessions(accountId)) {
            total += session.playtime;
        }
        return total;
    }

    /**
     * Gets the count of successful play sessions for an account.
     *
     * @param accountId the account ID
     * @return number of successful sessions
     */
    public int getSuccessfulSessionCount(@NonNull String accountId) {
        int count = 0;
        for (OfflineSession session : getAccountSessions(accountId)) {
            if (session.successful) {
                count++;
            }
        }
        return count;
    }

    /**
     * Deletes a session record.
     *
     * @param sessionId the session ID
     * @return true if deleted successfully
     */
    public boolean deleteSession(@NonNull String sessionId) {
        File sessionFile = new File(storageDir, sessionId + ".session");
        return sessionFile.delete();
    }

    /**
     * Clears all sessions for an account.
     *
     * @param accountId the account ID
     * @return true if cleared successfully
     */
    public boolean clearAccountSessions(@NonNull String accountId) {
        try {
            for (OfflineSession session : getAccountSessions(accountId)) {
                deleteSession(session.sessionId);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
