package mos.quarkus.play.controller;

import io.quarkus.logging.Log;
import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import mos.quarkus.play.defs.ChatGoal;
import mos.quarkus.play.model.ApiKey;
import mos.quarkus.play.service.CachingService;
import mos.quarkus.play.service.OpenAIService;
import mos.quarkus.play.util.HttpHandler;

import java.io.IOException;
import java.net.URI;
import java.util.*;

import static mos.quarkus.play.service.CachingService.*;
import static mos.quarkus.play.util.HttpHandler.DAY_IN_SECONDS;
import static mos.quarkus.play.util.HttpHandler.SESSION_COOKIE_NAME;

@Path("/openAI")
@Blocking
public class OpenAIController {

    private final static String SYSTEM_INSTRUCTION = "You are a person in the age between 30 and 50 who is searching for a date. " +
            "You use are using a chat-app (like in Tinder), to talk to your potential partner. Please respond to his/her chat-messages! " +
            "Your goal is to convince your chat partner that you are the right partner for %s. " +
            "You should ask the relevant question regarding your goal by your fourth answer at the latest.";

    private final static Map<ChatGoal, String> SYSTEM_INSTRUCTION_GOAL = Map.of(
            ChatGoal.SEX, "an affair and casual sex dates",
            ChatGoal.RELATIONSHIP, "a serious relationship",
            ChatGoal.ACTIVITIES, "leisure activities and friendship");

    private final static String FIRST_NON_AI_MESSAGE = "Hey, nice to meet you here. Such a nice profile. :) I look forward to learning more about you. Have you had a good day so far? " +
            "What would you like to know about me before we pick up the phone or meet? ;)";

    @Inject
    HttpHandler httpHandler;

    @Inject
    CachingService cachingService;

    @Inject
    OpenAIService openAIService;

    @Inject
    @Named("englishValidator")
    Validator validator;


    @Location("openAI/apiKey.html")
    Template apiKeyTemplate;

    @Location("openAI/openAIChat.html")
    Template openAIChatTemplate;


    @Path("/openAIChat")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance start() {
        String apiKey = checkForApiKey();
        return openAIChatTemplate.data("apiKey", halfLength(apiKey));
    }


    @Path("/apiKey")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance apiKeyGet() {
        return apiKeyTemplate.instance();
    }


    @Path("/apiKey")
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance apiKeyPost(@FormParam("key") String key) {
        ApiKey apiKey = new ApiKey(key);
        List<String> errorMessages = validate(apiKey);
        if (errorMessages == null) {
            Log.info("Store api key!");
            setSessionValue(USER_SESSION_OPENAPI_KEY, apiKey.getValue());
            redirect("/openAI/openAIChat");
        }
        Log.warn("Validation error: " + errorMessages);
        return apiKeyTemplate.data("key", key, "apiKeyErrors", String.join(" AND ", errorMessages));
    }


    @Path("/chatGoalDefinition")
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance chatGoalDefinition(@FormParam("goal") ChatGoal goal) {
        String apiKey = checkForApiKey();
        Log.info("Setting chat goal to: " + goal);
        setSessionValue(USER_SESSION_CHAT_GOAL, goal);
        redirect("/openAI/chatting");
        return null;            // should be never reached because of the redirect before
    }


    @Path("/chatting")
    @GET
    @Produces(MediaType.TEXT_HTML)
    public TemplateInstance chattingShow() {
        return generateChatTemplate(null);
    }

    @Path("/chatting")
    @POST
    @Produces(MediaType.TEXT_HTML)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public TemplateInstance chattingPost(@FormParam("otherMessage") String otherMessage) {
        String apiKey = checkForApiKey();
        if (otherMessage == null || otherMessage.trim().length() < 5) {
            return generateChatTemplate(Collections.singletonMap("formErrors", "Please enter the last message of you chat partner above. Needs to be >= 5 characters!"));
        }
        List<String> chatList = storeNewMessage(otherMessage);
        String instruction = String.format(SYSTEM_INSTRUCTION, SYSTEM_INSTRUCTION_GOAL.get((ChatGoal) getSessionValue(USER_SESSION_CHAT_GOAL)));
        String generatedChatGptAnswer;
        try {
            generatedChatGptAnswer = openAIService.requestDatingChatAnswer(instruction, chatList, apiKey);
        } catch (IOException e) {
            Log.error("Error while requesting ChatGPT: ", e);
            String last = chatList.removeLast();
            return generateChatTemplate(Map.of("lastMessage", last, "formErrors", "OpenAI API returns with an error: " + e));
        }
        storeNewMessage(generatedChatGptAnswer);
        redirect("/openAI/chatting");
        return null;            // should be never reached because of the redirect before
    }


    //////

    private List<String> storeNewMessage(String otherMessage) {
        List<String> chatList = getSessionValue(USER_SESSION_CHAT_LIST);
        if (chatList == null) {
            chatList = new ArrayList<>();
        }
        chatList.add(otherMessage);
        setSessionValue(USER_SESSION_CHAT_LIST, chatList);
        return chatList;
    }


    private TemplateInstance generateChatTemplate(Map<String, Object> moreModelValues) {
        String apiKey = checkForApiKey();
        ChatGoal chatGoal = getSessionValue(USER_SESSION_CHAT_GOAL);
        List<String> chatList = getSessionValue(USER_SESSION_CHAT_LIST);
        if (chatList == null) {
            chatList = storeNewMessage(FIRST_NON_AI_MESSAGE);
        }
        TemplateInstance templateInstance = openAIChatTemplate.data(
                "apiKey", halfLength(apiKey),
                "chatGoal", chatGoal.name().toLowerCase(),
                "chatList", chatList
        );
        if (moreModelValues != null) {
            for (Map.Entry<String, Object> entry : moreModelValues.entrySet()) {
                templateInstance.data(entry.getKey(), entry.getValue());
            }
        }
        return templateInstance;
    }


    private String checkForApiKey() {
        String apiKey = getSessionValue(USER_SESSION_OPENAPI_KEY);
        if (apiKey == null) {
            Log.info("No APIKey in Session!");
            redirect("/openAI/apiKey");   // throws a RedirectException
        }
        return apiKey;
    }


    private String halfLength(String org) {
        int apiKeyLength = org.length();
        int substringLength = apiKeyLength / 2;
        int startCutPosition = substringLength / 2;
        int endCutPosition = startCutPosition + substringLength;
        return org.substring(0, startCutPosition) + " ... " + org.substring(endCutPosition);
    }


    private List<String> validate(Object bean) {
        Set<ConstraintViolation<Object>> violations = validator.validate(bean);
        if (violations.isEmpty()) {
            return null;
        } else {
            // There were validation errors
            List<String> validationErrors = new ArrayList<>();
            for (ConstraintViolation<?> violation : violations) {
                validationErrors.add(violation.getPropertyPath() + ": " + violation.getMessage());
            }
            return validationErrors;
        }
    }


    private <T> void setSessionValue(String name, T value) {
        String sessionId = httpHandler.getCookieValue(SESSION_COOKIE_NAME);
        if (sessionId == null) {
            sessionId = UUID.randomUUID().toString();
            httpHandler.setCookie(SESSION_COOKIE_NAME, sessionId, DAY_IN_SECONDS);
        }
        cachingService.setUserSessionValue(sessionId, name, value);
    }

    private <T> T getSessionValue(String name) {
        String sessionID = httpHandler.getCookieValue(SESSION_COOKIE_NAME);
        if (sessionID == null) {
            return null;
        }
        return cachingService.getUserSessionValue(sessionID, name);
    }

    private void redirect(String uri) {
        throw new RedirectionException(Response.Status.FOUND, URI.create(uri));
    }


    /////////

    // Log.info(cachingService.getUserSessionValue("123", "test"));
    // httpHandler.setCookie("myTest", "1234", HttpHandler.DAY_IN_SECONDS);
    // httpHandler.removeCookie("myTest");

}
