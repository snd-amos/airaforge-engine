package com.myengine;

/** Runtime: the renderer skips this GameObject's MeshRenderer (play mode).
 *  Editor: still drawn so it can be selected/edited. Pure tag — the model
 *  draw loop checks {@code getComponent(InvisibleComponent.class)}. */
class InvisibleComponent extends Component {
}
