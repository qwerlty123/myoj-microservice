package com.qwerlty.myojbackendaiservice.generation.sandbox;

public interface AuthoringSandboxVerifier {
    VerificationReport verify(VerificationRequest request);

    void invalidateTask(Long taskId);

    boolean isCircuitOpen();
}
