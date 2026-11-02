package com.clawmate.platform.gui

/** Session-only provenance for overlays whose root vanishes during dismissal. */
internal class GuiOverlayOwnership {
    private val owned = mutableSetOf<Int>()
    fun retain(visibleIds: Set<Int>) { owned.retainAll(visibleIds) }
    fun isOwned(id: Int, owner: String?, ownPackage: String): Boolean {
        if (owner == ownPackage) owned.add(id)
        else if (owner != null) owned.remove(id)
        return id in owned
    }
}
