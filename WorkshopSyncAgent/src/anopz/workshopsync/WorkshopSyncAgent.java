package anopz.workshopsync;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Project Zomboid dedicated-server agent.
 *
 * The server records each workshop item's install timestamp once, at boot, and sends it to every
 * joining client, which fails with "version mismatch" if its own timestamp differs. A client always
 * syncs to Steam's latest first, so any workshop update after boot locks clients out until restart.
 * This keeps GameServer.workshopTimeStamps equal to Steam's current update time instead.
 *
 * It does NOT update the files the server has loaded: scripts and Lua stay at the boot version
 * until the next restart.
 *
 * Usage: -javaagent:WorkshopSyncAgent.jar[=poll=SECONDS]
 */
public final class WorkshopSyncAgent {
    static final String TAG = "[WorkshopSyncAgent] ";

    public static void premain(String args) {
        long pollSeconds = 300;
        if (args != null) {
            for (String kv : args.split(",")) {
                if (kv.startsWith("poll=")) {
                    pollSeconds = Math.max(30, Long.parseLong(kv.substring(5).trim()));
                }
            }
        }
        // Never touch zombie.* here: this runs before the game's classes are initialised.
        // A broken agent must not stop the server from booting.
        try {
            Thread t = new Thread(new Worker(pollSeconds), "WorkshopSyncAgent");
            t.setDaemon(true);
            t.start();
            log("loaded, poll every " + pollSeconds + "s");
        } catch (Throwable e) {
            log("disabled, could not start: " + e);
        }
    }

    static void log(String msg) {
        System.out.println(TAG + msg);
    }

    static final class Worker implements Runnable {
        private final long pollSeconds;

        Worker(long pollSeconds) {
            this.pollSeconds = pollSeconds;
        }

        @Override
        public void run() {
            try {
                waitForInstall();
                boolean first = true;
                while (true) {
                    try {
                        if (sync(first)) {
                            first = false;
                        }
                    } catch (Throwable e) {
                        log("sync failed: " + e);
                    }
                    Thread.sleep(pollSeconds * 1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /** GameServerWorkshopItems.Install fills workshopTimeStamps; wait until it has. */
        private void waitForInstall() throws InterruptedException {
            while (true) {
                Thread.sleep(5000);
                try {
                    if (zombie.network.GameServer.workshopTimeStamps != null
                            && zombie.core.znet.SteamWorkshop.instance != null) {
                        return;
                    }
                } catch (Throwable ignored) {
                    // game classes not ready yet
                }
            }
        }

        /** @return true once Steam answered and the timestamps were compared */
        private boolean sync(boolean first) throws InterruptedException {
            Object[] boxed = zombie.network.GameServer.WorkshopItems.toArray();
            long[] ids = new long[boxed.length];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = (Long) boxed[i];
            }
            if (ids.length == 0) {
                return false;
            }

            Query q = new Query();
            long handle = zombie.core.znet.SteamWorkshop.instance.CreateQueryUGCDetailsRequest(ids, q);
            q.handle = handle;
            q.handleSet.countDown();
            if (!q.done.await(60, TimeUnit.SECONDS)) {
                zombie.core.znet.SteamWorkshop.instance.RemoveCallback(q);
                log("Steam query timed out");
                return false;
            }
            if (q.details == null) {
                log("Steam query failed");
                return false;
            }
            apply(ids, q.details, first);
            return true;
        }

        private void apply(long[] ids, zombie.core.znet.SteamUGCDetails[] details, boolean first) {
            long[] stamps = zombie.network.GameServer.workshopTimeStamps;
            int agree = 0;
            int updated = 0;
            for (int i = 0; i < ids.length && i < stamps.length; i++) {
                zombie.core.znet.SteamUGCDetails d = null;
                for (zombie.core.znet.SteamUGCDetails c : details) {
                    if (c != null && c.getID() == ids[i]) {
                        d = c;
                        break;
                    }
                }
                if (d == null || d.getTimeUpdated() == 0) {
                    continue;
                }
                long steam = d.getTimeUpdated();
                if (stamps[i] == steam) {
                    agree++;
                } else {
                    log("item " + ids[i] + " (" + d.getTitle() + ") timestamp " + stamps[i] + " -> " + steam);
                    stamps[i] = steam;
                    updated++;
                }
            }
            if (first) {
                log("checked " + ids.length + " items: " + agree + " matched Steam, " + updated + " adjusted");
            }
        }
    }

    /** Receives the result of one UGC details query on the game's Steam callback thread. */
    static final class Query implements zombie.core.znet.ISteamWorkshopCallback {
        volatile long handle;
        final CountDownLatch handleSet = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(1);
        volatile zombie.core.znet.SteamUGCDetails[] details;

        private boolean mine(long h) throws InterruptedException {
            // callbacks go to every registered listener; ignore other queries' results
            handleSet.await(5, TimeUnit.SECONDS);
            return h == handle;
        }

        @Override
        public void onItemQueryCompleted(long h, int numResults) {
            try {
                if (!mine(h)) {
                    return;
                }
                zombie.core.znet.SteamWorkshop sw = zombie.core.znet.SteamWorkshop.instance;
                zombie.core.znet.SteamUGCDetails[] out = new zombie.core.znet.SteamUGCDetails[numResults];
                for (int i = 0; i < numResults; i++) {
                    out[i] = sw.GetQueryUGCResult(h, i);
                }
                sw.RemoveCallback(this);
                sw.ReleaseQueryUGCRequest(h);
                details = out;
            } catch (Throwable e) {
                log("query callback failed: " + e);
            } finally {
                done.countDown();
            }
        }

        @Override
        public void onItemQueryNotCompleted(long h, int result) {
            try {
                if (!mine(h)) {
                    return;
                }
                zombie.core.znet.SteamWorkshop.instance.RemoveCallback(this);
                zombie.core.znet.SteamWorkshop.instance.ReleaseQueryUGCRequest(h);
            } catch (Throwable e) {
                log("query callback failed: " + e);
            } finally {
                done.countDown();
            }
        }

        @Override public void onItemCreated(long id, boolean legal) { }
        @Override public void onItemNotCreated(int result) { }
        @Override public void onItemUpdated(boolean legal) { }
        @Override public void onItemNotUpdated(int result) { }
        @Override public void onItemSubscribed(long id) { }
        @Override public void onItemNotSubscribed(long id, int result) { }
        @Override public void onItemDownloaded(long id) { }
        @Override public void onItemNotDownloaded(long id, int result) { }
    }
}
