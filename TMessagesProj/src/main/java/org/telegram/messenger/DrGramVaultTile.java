/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 36: Vault Quick Tile — one tap opens the hidden-chats vault
 * through LaunchActivity (extra drgram_open_vault).
 */
package org.telegram.messenger;

import android.content.Intent;
import android.service.quicksettings.TileService;

public class DrGramVaultTile extends TileService {

    @Override
    public void onClick() {
        try {
            Intent launch = new Intent(this, org.telegram.ui.LaunchActivity.class);
            launch.setAction(Intent.ACTION_MAIN);
            launch.putExtra("drgram_open_vault", true);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivityAndCollapse(launch);
        } catch (Throwable ignore) {
        }
    }
}
