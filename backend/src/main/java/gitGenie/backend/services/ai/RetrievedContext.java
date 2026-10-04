package gitGenie.backend.services.ai;

import java.util.List;

import gitGenie.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}
