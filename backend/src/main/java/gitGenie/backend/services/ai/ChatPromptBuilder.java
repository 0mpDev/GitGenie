package gitGenie.backend.services.ai;

import org.springframework.stereotype.Component;

@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repoFullName) {
        return """
                You are GitGenie, an expert code assistant for the GitHub repository "%s".
                Answer the user's question using ONLY the code context provided in the message.
                - Mention file paths when you refer to code (they appear as "// File: <path>" headers).
                - If the context does not contain the answer, say so plainly instead of guessing.
                - Use Markdown, and put code in fenced code blocks with a language tag.
                """.formatted(repoFullName);
    }

    public String userPrompt(String contextText, String question) {
        return """
                Code context:
                %s

                Question:
                %s
                """.formatted(contextText, question);
    }
}