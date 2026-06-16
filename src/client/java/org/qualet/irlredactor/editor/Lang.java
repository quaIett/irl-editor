package org.qualet.irlredactor.editor;

import net.minecraft.client.resource.language.I18n;

/**
 * Thin wrapper over Minecraft's client {@link I18n} so the ImGui editor follows
 * the language selected in the game options. {@code I18n.translate} reads the
 * current {@code Language} instance on every call (and Minecraft swaps it on a
 * language change), so the UI re-localizes live without a restart.
 *
 * <p>Strings live in {@code assets/irl-redactor/lang/*.json}; missing keys fall
 * back to {@code en_us}. Both {@code en_us} and {@code ru_ru} are provided, so any
 * other in-game language shows the English text (rendered with the bundled font,
 * whose atlas carries Basic Latin + Cyrillic glyphs).</p>
 */
public final class Lang
{
    private Lang()
    {
    }

    /** Translate {@code key} for the current game language, formatting with {@code args}
     *  ({@code I18n.translate} runs {@code String.format} on the template). */
    public static String t(String key, Object... args)
    {
        return I18n.translate(key, args);
    }
}
