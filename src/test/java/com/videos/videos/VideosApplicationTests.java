package com.videos.videos;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "video.generator.enabled=false")
class VideosApplicationTests {

	@Test
	void contextLoads() {
	}

	@Test
	void sanitizesVideoNameForWindows() {
		assertEquals("my-video-name", PhotoVideoGenerator.sanitizeVideoName("my/video:name.mp4"));
		assertEquals("-CON", PhotoVideoGenerator.sanitizeVideoName("CON.mp4"));
		assertEquals("photo-", PhotoVideoGenerator.sanitizeVideoName("photo."));
		assertEquals("video", PhotoVideoGenerator.sanitizeVideoName(""));
	}

}
