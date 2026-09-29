package com.psiog.perfinsight.ingest.mapping;

import com.psiog.perfinsight.activity.ActivityType;
import com.psiog.perfinsight.identity.ActorRef;

import java.time.Instant;
import java.util.Map;

public record NormalizedActivity(ActivityType type, String externalId, String title, String url,
                                 Instant occurredAt, ActorRef actor, Map<String, Object> attributes) {}
