package com.vision.app;

/** Generation-bound draft-only voice session. It cannot submit or approve commands. */
public final class VisionVoiceSession {
    private long generation;
    private boolean active;
    private String draft;
    public long start(String currentDraft) { generation++;active=true;draft=currentDraft;return generation; }
    public boolean accepts(long token,String currentDraft,String text) {
        return active && token==generation && draft.equals(currentDraft) && text!=null
                && !text.trim().isEmpty() && text.length()<=512;
    }
    public boolean isCurrent(long token) { return active && token==generation; }
    public void cancel() { active=false;generation++; }
}
