package com.myengine;

/** Editor trigger volume. When the {@link Player}'s position enters the
 *  host entity's world AABB, the player is respawned. Pure tag — behavior
 *  is driven from {@code VulkanMain.checkWinZones()} (the Vulkan runtime
 *  loop; the legacy OpenGL path had its own private equivalent). */
class WinZoneComponent extends Component {
}
