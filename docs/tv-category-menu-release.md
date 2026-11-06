# TV category menu release handling

The active TV folder panel opens an AlertDialog from combinedClickable's long
press. Hide is its first action. A confirm release belonging to the original
folder can arrive after dialog focus transfers, so the dialog must not treat
that release or the remaining repeats as a fresh confirmation.

MenuConfirmKeyGuard now requires a fresh, non-repeated confirm down inside each
dialog before accepting its matching, uncancelled up. It covers D-pad center,
Enter, numpad Enter and gamepad A; directional navigation and Back retain their
normal behavior. Pointer/touch clicks remain supported. The legacy sidebar also
ignores repeated confirms while its menu is open.

Hidden categories intentionally remain absent from playable TV navigation. That
made the active folder menu's hidden-row Unhide branch unreachable. A separate
Unhide action is available inside the existing long-press category menu whenever
hidden provider groups exist. Long-pressing All Channels also opens that menu,
so the last hidden provider group remains recoverable. Selecting Unhide then a
group restores it through the existing source-scoped callback. There is no new
sidebar control; the initially added shortcut was removed at the user's request.

Regression checks dispatch held repeats and an orphan release into the actual
dialog, then a fresh confirmation, verify all three move callbacks and Cancel,
and hide/restore the last provider category. Existing category/index tests run
alongside them. No playlist credentials, channel sources or saved favourites
are changed. GitHub Build Check validates this together with the November 6
Sentry follow-up. No APK publication is included.
