package com.example.llamastackandroiddemo

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.llama.llamastack.client.LlamaStackClientClient
import com.llama.llamastack.client.okhttp.LlamaStackClientOkHttpClient
import com.llama.llamastack.core.JsonValue
import com.llama.llamastack.models.AgentConfig
import com.llama.llamastack.models.AgentCreateParams
import com.llama.llamastack.models.AgentSessionCreateParams
import com.llama.llamastack.models.AgentTurnCreateParams
import com.llama.llamastack.models.CompletionMessage
import com.llama.llamastack.models.InferenceChatCompletionParams
import com.llama.llamastack.models.InterleavedContent
import com.llama.llamastack.models.SamplingParams
import com.llama.llamastack.models.SystemMessage
import com.llama.llamastack.models.ToolDef
import com.llama.llamastack.models.ToolResponseMessage
import com.llama.llamastack.models.UserMessage
import com.llama.llamastack.services.blocking.agents.TurnService
import kotlinx.datetime.Clock
import java.io.File
import java.net.URLConnection
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

interface InferenceStreamingCallback {
    fun onStreamReceived(message: String)
    fun onStatStreamReceived(tps: Float)
}

class ExampleLlamaStackRemoteInference(remoteURL: String) {

    var client: LlamaStackClientClient? = null

    init {
        try {
            client = LlamaStackClientOkHttpClient
                .builder()
                .baseUrl(remoteURL)
                .headers(mapOf("x-llamastack-client-version" to listOf("0.1.0")))
                .build()
        } catch (e: Exception) {
            client = null
            AppLogging.getInstance().log(e.message)
        }
    }

    fun inferenceStartWithoutAgent(modelName: String, temperature: Double, prompt: ArrayList<Message>, userProvidedSystemPrompt:String, ctx: Context): String {
        val future = CompletableFuture<String>()
        val thread = Thread {
            try {
                val response = inferenceCallWithoutAgent(modelName, temperature, prompt, userProvidedSystemPrompt, ctx, true);
                future.complete(response)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        thread.start();
        return future.get();
    }

    fun inferenceStartWithAgent(agentId: String, sessionId: String, turnService: TurnService, prompt: ArrayList<Message>, ctx: Context): String {
        val future = CompletableFuture<String>()
        val thread = Thread {
            try {
                val response = remoteAgentInference(agentId, sessionId, turnService, prompt, ctx)
                future.complete(response)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        thread.start();
        return future.get();
    }



    //Example running simple inference + tool calls without using agent's workflow
    private fun inferenceCallWithoutAgent(modelName: String, temperature: Double, conversationHistory: ArrayList<Message>, userProvidedSystemPrompt: String, ctx: Context, streaming: Boolean): String {
        if (client == null) {
            AppLogging.getInstance().log("client is null for remote inference");
            return "[ERROR] client is null for remote inference"
        }
        //Get the current time in ISO format and pass it to the model in system prompt as a reference. This is useful for any scheduling and vague timing reference from user prompt.
        val zdt = ZonedDateTime.ofInstant(Instant.parse(Clock.System.now().toString()), ZoneId.systemDefault())
        val formattedZdt = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val availableFunctions = AvailableFunctions.getInstance()
        val functionDefinitions = availableFunctions.values()
        var instruction = userProvidedSystemPrompt
        //If no System prompt configured by the user, use default tool call system prompt
        if (instruction == "") {
            instruction = """
                            Today Date: $formattedZdt
                    
                            Tool Instructions:
                            - When user is asking a question that requires your reasoning, do not use a function call or generate functions.
                            - Only function call if user's intention matches the function that you have access to.
                            - When looking for real time information use relevant functions if available.
                            - Ignore previous conversation history if you are making a tool call.

                                           
                            You have access to the following functions:
                            {$functionDefinitions}
                                      
                            If you decide to invoke any of the function(s), you MUST put it in the format of [func_name1(params_name1=params_value1, params_name2=params_value2...), func_name2(params)]\n
                            You SHOULD NOT include any other text in the response.
                    
                            Reminder:                          
                            - Function calls MUST follow the specified format
                            - Required parameters MUST be specified
                            - Only call one function at a time
                            - Put the entire function call reply on one line
                            - When returning a function call, don't add anything else to your response
                            - When scheduling the events, make sure you set the date and time right. Use step by step reasoning for date such as next Tuesday
                         """
        }

        var response = ""
        try {
            if (streaming) {
                val result = client!!.inference().chatCompletionStreaming(
                    InferenceChatCompletionParams.builder()
                        .modelId(modelName)
                        .samplingParams(
                            SamplingParams.builder()
                                .strategy(
                                    SamplingParams.Strategy.ofGreedySampling()
                                ).build()
                        )
                        .messages(
                            constructLSMessagesFromConversationHistoryAndSystemPrompt(conversationHistory, instruction)
                        )
                        .build()
                )
                val callback = ctx as InferenceStreamingCallback
                result.use {
                    result.asSequence().forEach {
                        val delta = it.event().delta()

                        if (delta.isToolCall()) {
                            val toolCall = delta.toolCall()?.toolCall()
                            if (toolCall != null) {
                                callback.onStreamReceived("\n" + functionDispatchWithoutAgent(listOf(toolCall.asToolCall()), ctx))
                            } else {
                                callback.onStreamReceived("\n" + "Empty tool call. File a bug")
                            }

                        }
                        if (it.event().stopReason().toString() != "end_of_turn") {
                            callback.onStreamReceived(
                                it.event().delta().text()
                                    ?.text()
                                    .toString())
                        }
                    }
                }
            } else {
                val result = client!!.inference().chatCompletion(
                    InferenceChatCompletionParams.builder()
                        .modelId(modelName)
                        .samplingParams(
                            SamplingParams.builder()
                                .strategy(
                                    SamplingParams.Strategy.ofGreedySampling()
                                ).build()
                        )
                        .messages(
                            constructLSMessagesFromConversationHistoryAndSystemPrompt(conversationHistory, instruction)
                        )
                        .build()
                )
                response = result.completionMessage().content().string().toString();
                if (response == "") {
                    //Empty content as Llama Stack is returning a tool call in non-streaming mode
                    val toolCalls = result.completionMessage().toolCalls()
                    if (toolCalls != null) {
                        return if (toolCalls.isNotEmpty()) {
                            functionDispatch(toolCalls, ctx)
                        } else {
                            "Empty tool calls and model response. File a bug"
                        }
                    }
                }
            }
        } catch (e : Exception) {
            AppLogging.getInstance().log("Exception on remote inference " + e.message);
            return "Exception on remote inference " + e.message
        }
        return response
    }

    private fun constructLSMessagesFromConversationHistoryAndSystemPrompt(
        conversationHistory: ArrayList<Message>,
        systemPrompt: String
    ):List<com.llama.llamastack.models.Message> {
        val messageList = ArrayList<com.llama.llamastack.models.Message>();
        // System prompt
        messageList.add(
            com.llama.llamastack.models.Message.ofSystem(
                SystemMessage.builder()
                    .content(InterleavedContent.ofString(systemPrompt))
                    .build()
            )
        )
        // User and assistant messages
        for (chat in conversationHistory) {
            var inferenceMessage: com.llama.llamastack.models.Message
            if (chat.isSent) {
                // User message
                inferenceMessage = com.llama.llamastack.models.Message.ofUser(
                    UserMessage.builder()
                        .content(InterleavedContent.ofString(chat.text))
                        .build()
                )
            } else {
                // Assistant message (aka previous prompt response)
                inferenceMessage = com.llama.llamastack.models.Message.ofCompletion(
                    CompletionMessage.builder()
                        .content(InterleavedContent.ofString(chat.text))
                        .stopReason(CompletionMessage.StopReason.END_OF_MESSAGE)
                        .toolCalls(emptyList())
                        .build()
                )
            }
            messageList.add(inferenceMessage)
        }
        AppLogging.getInstance().log("conversation history length "  + messageList.size)
        return messageList
    }


    fun createRemoteAgent(modelName: String, temperature: Double, userProvidedSystemPrompt: String, ctx: Context): Triple<String, String, TurnService> {
        val agentConfig = createRemoteAgentConfig(modelName, temperature, userProvidedSystemPrompt)
        val agentService = client!!.agents()
        val agentCreateResponse = agentService.create(
            AgentCreateParams.builder()
                .agentConfig(agentConfig)
                .build(),
        )

        val agentId = agentCreateResponse.agentId()
        val sessionService = agentService.session()
        val agentSessionCreateResponse = sessionService.create(
            AgentSessionCreateParams.builder()
                .agentId(agentId)
                .sessionName("test-session")
                .build()
        )

        val sessionId = agentSessionCreateResponse.sessionId()
        val turnService = agentService.turn()
        return Triple(agentId, sessionId, turnService)
    }

    //Example of running inference with customize tool calls using agent workflow.
    //Note Agent inference only support streaming at the moment.
    private fun remoteAgentInference(agentId: String, sessionId: String, turnService: TurnService, conversationHistory: ArrayList<Message>, ctx: Context): String {
        val agentTurnCreateResponseStream =
            turnService.createStreaming(
                AgentTurnCreateParams.builder()
                    .agentId(agentId)
                    .messages(
                        constructMessagesForAgent(conversationHistory, ctx)
                    )
                    .sessionId(sessionId)
                    .build()
            )
        val callback = ctx as InferenceStreamingCallback
        agentTurnCreateResponseStream.use {
            agentTurnCreateResponseStream.asSequence().forEach {
                val agentResponsePayload = it.event().payload()
                when {
                    agentResponsePayload.isAgentTurnResponseTurnStart() -> {
                        // Handle Turn Start Payload
                    }
                    agentResponsePayload.isAgentTurnResponseStepStart() -> {
                        // Handle Step Start Payload
                    }
                    agentResponsePayload.isAgentTurnResponseStepProgress() -> {
                        // Handle Step Progress Payload
                        val result = agentResponsePayload.agentTurnResponseStepProgress()?.delta()?.text()?.text()
                        if (result != null) {
                            callback.onStreamReceived(result.toString())
                        }
                    }
                    agentResponsePayload.isAgentTurnResponseStepComplete() -> {
                        // Handle Step Complete Payload
                        val toolCalls = agentResponsePayload.agentTurnResponseStepComplete()?.stepDetails()?.asInferenceStep()?.modelResponse()?.toolCalls()
                        if (!toolCalls.isNullOrEmpty()) {
                            callback.onStreamReceived(functionDispatch(toolCalls, ctx))
                        }
                    }
                    agentResponsePayload.isAgentTurnResponseTurnComplete() -> {
                        // Handle Turn Complete Payload
                    }
                }
            }
        }
        return ""
    }

    private fun createRemoteAgentConfig(modelName: String, temperature: Double, userProvidedSystemPrompt: String): AgentConfig {
        //Get the current time in ISO format and pass it to the model in system prompt as a reference. This is useful for any scheduling and vague timing reference from user prompt.
        val zdt = ZonedDateTime.ofInstant(Instant.parse(Clock.System.now().toString()), ZoneId.systemDefault())
        //This should be replaced with Agent getting date and time with search tool
        val formattedZdt = zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val clientTools = mutableListOf<ToolDef>()
        var instruction = userProvidedSystemPrompt
        //If no System prompt configured by the user, use default tool call system prompt
        if (instruction == "") {
            clientTools.add(CustomTools.getCreateCalendarEventTool())
            instruction = "Think step by step to decide if you need to generate a tool call based on tools available to you. If not, just answer the question. If you decide to generate function, reply with the function you only. For your reference, Today Date is $formattedZdt."
        }
        //Llama 1B/3B text model only support PYTHON_LIST at the moment. Whereas Vision instruction models only support JSON format.
        var toolPromptFormat = AgentConfig.ToolPromptFormat.PYTHON_LIST
        if (modelName == "meta-llama/Llama-3.1-8B-Instruct" || modelName == "meta-llama/Llama-3.2-11B-Vision-Instruct" || modelName == "meta-llama/Llama-3.2-90B-Vision-Instruct") {
            toolPromptFormat = AgentConfig.ToolPromptFormat.JSON
        }

        val agentConfig =
            AgentConfig.builder()
                .enableSessionPersistence(false)
                .instructions(instruction)
                .maxInferIters(100)
                .model(modelName)
                .samplingParams(
                    SamplingParams.builder()
                        .strategy(
                            SamplingParams.Strategy.ofGreedySampling()
                        )
                        .build()
                )
                .toolChoice(AgentConfig.ToolChoice.AUTO)
                .toolPromptFormat(toolPromptFormat)
                .clientTools(
                    clientTools
                )
                .build()

        return agentConfig
    }

    private fun constructMessagesForAgent(
        conversationHistory: ArrayList<Message>, ctx: Context
    ):List<AgentTurnCreateParams.Message> {
        val messageList = ArrayList<AgentTurnCreateParams.Message>();
        var image : InterleavedContent.ImageContentItem.Image? = null
        // User and assistant messages
        for (chat in conversationHistory) {
            var inferenceMessage: AgentTurnCreateParams.Message

            if (chat.isSent) {
                // First image in the chat. Image must pair with a prompt
                if (chat.messageType == MessageType.IMAGE && image == null) {
                    val imageUri = Uri.parse(chat.imagePath)
                    val contentResolver = ctx.contentResolver
                    val imageFilePath = getFilePathFromUri(contentResolver, imageUri)
                    val imageDataUrl = imageFilePath?.let { encodeImageToDataUrl(it) }
                    val imageUrl = imageDataUrl?.let { InterleavedContent.ImageContentItem.Image.Url.builder().uri(it).build() }
                    if (imageUrl != null) {
                        image = InterleavedContent.ImageContentItem.Image.builder().url(imageUrl).build()
                    }

                    continue
                }
                // Prompt right after the image
                else if (chat.messageType == MessageType.TEXT && image != null) {

                    inferenceMessage = AgentTurnCreateParams.Message.ofUser(
                        UserMessage.builder()
                            .content(InterleavedContent.ofString(chat.text))
                            .build()
                    )
                    messageList.add(inferenceMessage)

                    inferenceMessage = AgentTurnCreateParams.Message.ofUser(
                            UserMessage.builder()
                                .content(InterleavedContent.ofImageContentItem(
                                    InterleavedContent.ImageContentItem.builder()
                                        .image(image)
                                        .type(JsonValue.from("image"))
                                        .build()
                                ))
                                .build()
                    )
                    image = null
                }
                //Everything else. No multiple images support yet
                else {
                    // User message
                    inferenceMessage = AgentTurnCreateParams.Message.ofUser(
                        UserMessage.builder()
                            .content(InterleavedContent.ofString(chat.text))
                            .build()
                    )
                }
            }
            else {
                // Assistant message (aka previous prompt response)
                inferenceMessage = AgentTurnCreateParams.Message.ofToolResponse(
                    ToolResponseMessage.builder()
                        .callId("")
                        .content(InterleavedContent.ofString(chat.text))
                        .toolName("")
                        .build()
                )
            }
            messageList.add(inferenceMessage)
        }

        AppLogging.getInstance().log("conversation history length "  + messageList.size)
        return messageList
    }

    //Image reasoning processing
    private fun encodeImageToDataUrl(filePath: String): String {
        val mimeType = URLConnection.guessContentTypeFromName(filePath)
            ?: throw RuntimeException("Could not determine MIME type of the file")
        val imageFile = File(filePath)
        val encodedString = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        return "data:$mimeType;base64,$encodedString"
    }

    private fun getFilePathFromUri(contentResolver: ContentResolver, uri: Uri): String? {
        var filePath: String? = null
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val cursor: Cursor? = contentResolver.query(uri, projection, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val columnIndex = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                filePath = it.getString(columnIndex)
            }
        }
        return filePath
    }
}