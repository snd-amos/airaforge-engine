package com.myengine;

/** Collision mask toggle: the physics grid skips this GameObject, so the
 *  player walks through it (trigger / non-solid). Pure tag — read by
 *  {@link World#updateGrid()}. */
class WalkthroughComponent extends Component {
}
