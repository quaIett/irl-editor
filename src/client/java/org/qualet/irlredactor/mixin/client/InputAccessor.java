package org.qualet.irlredactor.mixin.client;

import net.minecraft.client.input.Input;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Write access to {@code Input.movementVector} (a protected field on the base
 * {@code Input} class) so the free camera can zero the player's analog movement.
 *
 * <p>A subclass {@code @Shadow} of the inherited field does NOT attach when the
 * mixin targets {@code KeyboardInput} (the field lives on the superclass
 * {@code Input}) — that fails at mixin-apply with "field was not located in the
 * target class". An {@code @Accessor} on the DECLARING class ({@code Input}) is
 * the robust way to reach a protected/inherited field.</p>
 */
@Mixin(Input.class)
public interface InputAccessor
{
    @Accessor("movementVector")
    void setMovementVector(Vec2f value);
}
