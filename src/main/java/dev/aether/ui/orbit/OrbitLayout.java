package dev.aether.ui.orbit;

import org.joml.Vector3d;

// pure camera and panel placement for one frame, so the in-game screen and the preview harness share it
final class OrbitLayout {
    static final float PANEL_W = 500f;
    static final float PANEL_H = 560f;
    static final float MODULE_W = 620f;
    static final float MODULE_H = 640f;

    // every input one frame needs; open is the open/close progress 0..1 already eased
    record Input(Vector3d anchor, float yaw, float pitch, double eyeHeight, float baseFov, int count, float ring,
                 float zoom, float expand, float open, boolean settled, float clock, float[] unfold, int active,
                 double[] leanPos, double[] leanLook, double windowHeight) {
    }

    record Camera(Vector3d pos, Vector3d look, Vector3d forward, Vector3d right, Vector3d up, float fov) {
    }

    record Placement(int index, Vector3d center, Vector3d right, Vector3d up, double width, double height,
                     float designW, float designH, float alpha, float dim, boolean active) {
        Vector3d corner(double sx, double sy) {
            return new Vector3d(center).fma(sx * width / 2, right).fma(sy * height / 2, up);
        }

        Vector3d normal() {
            return new Vector3d(right).cross(up);
        }
    }

    record Result(Camera camera, Placement[] placements) {
    }

    private OrbitLayout() {
    }

    // device pixels per design unit for the front panel, so its text maps 1:1 onto the screen
    static double pixelRatio(double windowHeight) {
        return Math.max(0.75, Math.min(2.5, windowHeight * 0.64 / PANEL_H));
    }

    static double wrap(double o, int count) {
        double m = ((o % count) + count) % count;
        return m >= count / 2.0 ? m - count : m;
    }

    static Result compute(Input in) {
        Camera camera = camera(in);
        return new Result(camera, panels(in, camera));
    }

    private static Camera camera(Input in) {
        float e = in.open();
        double ex = in.expand();
        Vector3d tpPos = new Vector3d(OrbitRig.TP_POS).add(in.leanPos()[0] - ex * 1.3, in.leanPos()[1], in.leanPos()[2] - ex * 0.5);
        Vector3d tpLook = new Vector3d(OrbitRig.TP_LOOK).add(in.leanLook()[0], in.leanLook()[1], in.leanLook()[2]);
        Vector3d fpPos = new Vector3d(0, in.eyeHeight(), 0);
        Vector3d fpLook = new Vector3d(0, in.eyeHeight() - Math.tan(Math.toRadians(in.pitch())) * 10, 10);
        double eb = 1 - Math.pow(1 - e, 2.2);
        Vector3d pos = new Vector3d(
                OrbitRig.lerp(fpPos.x, tpPos.x, e),
                OrbitRig.lerp(fpPos.y, tpPos.y, e) + Math.sin(Math.min(1, eb * 1.15) * Math.PI) * 1.5,
                OrbitRig.lerp(fpPos.z, tpPos.z, eb));
        Vector3d look = new Vector3d(fpLook).lerp(tpLook, e);
        float zz = OrbitRig.smooth(OrbitRig.clamp(in.zoom(), 0f, 1f)) * e;
        pos.lerp(OrbitRig.OV_POS, zz);
        look.lerp(OrbitRig.OV_LOOK, zz);
        float fov = (float) OrbitRig.lerp(OrbitRig.lerp(in.baseFov(), OrbitRig.FOV, e), OrbitRig.OV_FOV, zz);
        float breathe = (in.settled() ? 1f : e) * (1f - in.zoom());
        pos.add(Math.sin(in.clock() * 0.37) * 0.035 * breathe, Math.sin(in.clock() * 0.83) * 0.03 * breathe, 0);

        Vector3d worldPos = toWorld(in, pos, new Vector3d());
        Vector3d worldLook = toWorld(in, look, new Vector3d());
        Vector3d forward = new Vector3d(worldLook).sub(worldPos).normalize();
        Vector3d right = new Vector3d(forward).cross(0, 1, 0).normalize();
        Vector3d up = new Vector3d(right).cross(forward).normalize();
        return new Camera(worldPos, worldLook, forward, right, up, fov);
    }

    private static Placement[] panels(Input in, Camera cam) {
        int count = in.count();
        double step = Math.PI * 2 / count;
        double focal = (in.windowHeight() / 2) / Math.tan(Math.toRadians(cam.fov()) / 2);
        double persp = focal / pixelRatio(in.windowHeight());
        float z = in.zoom();
        Vector3d frontWorld = toWorld(in, OrbitRig.slot(0, 0, 0, step, new Vector3d()), new Vector3d());
        double s0 = new Vector3d(frontWorld).sub(cam.pos()).dot(cam.forward()) / persp;
        Vector3d chest = toWorld(in, new Vector3d(0, 1.3, 0), new Vector3d());
        Placement[] out = new Placement[count];
        for (int i = 0; i < count; i++) {
            double o = wrap(i - in.ring(), count);
            boolean active = i == in.active();
            double e = active ? in.expand() : 0;
            if (!active && in.expand() > 0.001) o += Math.signum(o) * 0.24 * in.expand();
            double ao = Math.abs(o);
            Vector3d rig = OrbitRig.slot(o, z, e, step, new Vector3d());
            Vector3d center = toWorld(in, rig, new Vector3d());
            float u = in.unfold()[i];
            if (u < 0.999f) center.lerp(chest, 1 - OrbitRig.clamp(u, 0f, 1f));

            Vector3d inward = dirToWorld(in, new Vector3d(-rig.x, 0, -rig.z), new Vector3d());
            if (inward.lengthSquared() < 1e-6) inward.set(cam.forward()).negate();
            inward.normalize();
            double w = Math.max(OrbitRig.clamp((float) (1 - ao), 0f, 1f), z * z);
            Vector3d normal = new Vector3d(inward).lerp(new Vector3d(cam.forward()).negate(), w).normalize();
            Vector3d up = new Vector3d(0, 1, 0).lerp(cam.up(), w).normalize();
            Vector3d right = new Vector3d(up).cross(normal).normalize();
            up.set(normal).cross(right).normalize();

            double sf = OrbitRig.lerp(1 - 0.27 * Math.min(ao, 2), 0.33, z);
            double crisp = new Vector3d(center).sub(cam.pos()).dot(cam.forward()) / persp;
            double s = OrbitRig.lerp(s0 * sf, crisp, w * (1 - z)) * Math.max(0.0001, u);
            boolean moduleView = active && in.expand() > 0.5;
            float dw = moduleView ? MODULE_W : PANEL_W;
            float dh = moduleView ? MODULE_H : PANEL_H;
            float dim = (float) OrbitRig.lerp(OrbitRig.clamp((float) (ao * 0.62), 0f, 0.74f), active ? 0 : 0.2, z);
            float alpha = OrbitRig.clamp(u * 1.3f, 0f, 1f);
            out[i] = new Placement(i, center, right, up, dw * s, dh * s, dw, dh, alpha, dim, active);
        }
        return out;
    }

    // rig space to world: +z is where the player faced on open, +x their left, around their feet
    static Vector3d toWorld(Input in, Vector3d rig, Vector3d out) {
        double yaw = Math.toRadians(in.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        Vector3d a = in.anchor();
        return out.set(a.x + lx * rig.x + fx * rig.z, a.y + rig.y, a.z + lz * rig.x + fz * rig.z);
    }

    static Vector3d dirToWorld(Input in, Vector3d rig, Vector3d out) {
        double yaw = Math.toRadians(in.yaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double lx = Math.cos(yaw), lz = Math.sin(yaw);
        return out.set(lx * rig.x + fx * rig.z, rig.y, lz * rig.x + fz * rig.z);
    }

    // ray through a screen point; gx/gy in the same units as width/height
    static Vector3d ray(Camera cam, double gx, double gy, double width, double height) {
        double ndcX = (gx / width) * 2 - 1;
        double ndcY = 1 - (gy / height) * 2;
        double t = Math.tan(Math.toRadians(cam.fov()) / 2);
        double aspect = width / height;
        return new Vector3d(cam.forward()).fma(ndcX * t * aspect, cam.right()).fma(ndcY * t, cam.up()).normalize();
    }

    // panel-local design coordinates where a ray meets the panel's plane; allowOutside=false returns null off the panel
    static float[] hit(Camera cam, Placement p, Vector3d dir, boolean allowOutside) {
        Vector3d normal = p.normal();
        double denom = dir.dot(normal);
        if (Math.abs(denom) < 1e-6) return null;
        double t = new Vector3d(p.center()).sub(cam.pos()).dot(normal) / denom;
        if (t <= 0) return null;
        Vector3d at = new Vector3d(cam.pos()).fma(t, dir).sub(p.center());
        double u = at.dot(p.right()) / p.width() + 0.5;
        double v = 0.5 - at.dot(p.up()) / p.height();
        if (!allowOutside && (u < 0 || u > 1 || v < 0 || v > 1)) return null;
        return new float[]{(float) (u * p.designW()), (float) (v * p.designH())};
    }
}
