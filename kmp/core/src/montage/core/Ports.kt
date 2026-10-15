// SPDX-License-Identifier: Apache-2.0
package montage.core

/** Complete upstream MCP capability inventory. These are contracts, not implemented tools. */
enum class MontageTool(val wireName: String) {
    SelectAsset("select_asset"),
    StoryboardByText("generate_storyboard_by_text"),
    StoryboardByImage("generate_storyboard_by_image"),
    GenerateImages("generate_images"),
    ResizeImage("resize_image"),
    GenerateVideos("generate_videos"),
    GenerateVideosOmni("generate_videos_omni"),
    GenerateVideosWithReferencesOmni("generate_videos_with_references_omni"),
    ConcatenateVideos("concatenate_videos"),
    GenerateBgm("generate_bgm"),
    GenerateNarrative("generate_narrative"),
    GenerateVoiceover("generate_voiceover"),
    RenderFinalVideo("render_final_video"),
}

data class SessionRef(val app: String, val user: String, val id: String)

/** Cloud authentication, ADK event streaming and persistence are implemented behind this boundary later. */
interface SessionGateway {
    suspend fun create(app: String, user: String): SessionRef
    suspend fun fork(source: SessionRef, upToEventId: String): SessionRef
}

/** Platform pickers return opaque handles: common code must not assume filesystem paths. */
data class MediaHandle(val value: String, val mimeType: String)
interface PlatformMedia {
    suspend fun pickImages(): List<MediaHandle>
    suspend fun saveVideo(sourceUrl: String, suggestedName: String): MediaHandle?
}
