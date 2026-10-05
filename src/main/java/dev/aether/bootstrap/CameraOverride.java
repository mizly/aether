package dev.aether.bootstrap;

// a render-only camera pose; nothing about the player entity changes, so nothing reaches the server
public record CameraOverride(double x, double y, double z, float yRot, float xRot, float fov) {
}
