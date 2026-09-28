package ru.refontstudio.refontcrafts.gui;

final class GhostCursorPolicy {
    private GhostCursorPolicy() {
    }

    static boolean canMoveInsideEditor(boolean pending, boolean unsafeClick,
                                       boolean shiftClick, boolean numberKey) {
        return !pending && !unsafeClick && !shiftClick && !numberKey;
    }
}
