package com.qwerlty.myojbackendaiservice.authoring.api;

import java.util.List;

public record AuthoringTaskPage(
        List<AuthoringTaskView> records,
        long total,
        int current,
        int pageSize
) {
}
