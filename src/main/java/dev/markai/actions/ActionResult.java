package dev.markai.actions;

/** Accepted navigation is deliberately different from confirmed completion. */
public record ActionResult(Status status, String code, String message, long gameTime) {
    public enum Status { NONE, ACCEPTED, SUCCEEDED, FAILED, CANCELLED }

    public ActionResult {
        if (status == null) status = Status.NONE;
        code = bounded(code, 64);
        message = bounded(message, 240);
        gameTime = Math.max(0, gameTime);
    }

    public boolean success() { return status == Status.SUCCEEDED; }
    public static ActionResult none() { return new ActionResult(Status.NONE, "none", "", 0); }
    public static ActionResult accepted(String message, long time) {
        return new ActionResult(Status.ACCEPTED, "accepted", message, time);
    }
    public static ActionResult succeeded(String message, long time) {
        return new ActionResult(Status.SUCCEEDED, "completed", message, time);
    }
    public static ActionResult failed(String code, String message, long time) {
        return new ActionResult(Status.FAILED, code, message, time);
    }
    public static ActionResult cancelled(String message, long time) {
        return new ActionResult(Status.CANCELLED, "cancelled", message, time);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return "";
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
