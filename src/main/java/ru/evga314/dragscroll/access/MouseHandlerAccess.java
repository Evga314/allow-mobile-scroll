package ru.evga314.dragscroll.access;

/** Private state of MouseHandler, exposed by MouseHandlerMixin. */
public interface MouseHandlerAccess {
    /**
     * Drops the cursor movement accumulated since the last frame. The launcher
     * teleports the cursor to each new finger; if that jump reached
     * handleAccumulatedMovement(), vanilla would treat it as a drag.
     */
    void dragscroll$clearAccumulatedMovement();
}
