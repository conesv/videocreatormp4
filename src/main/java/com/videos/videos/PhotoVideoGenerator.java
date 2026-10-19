package com.videos.videos;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;

@Component
@ConditionalOnProperty(name = "video.generator.enabled", havingValue = "true", matchIfMissing = true)
public class PhotoVideoGenerator implements CommandLineRunner {
	private static final Logger logger = LoggerFactory.getLogger(PhotoVideoGenerator.class);
	private static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "bmp", "gif");
	private static final Pattern INVALID_FILENAME_CHARACTERS = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
	private static final Pattern RESERVED_FILENAME = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");

	@Value("${video.input-dir:imagenes}")
	private String inputDirectory;

	@Value("${video.output-file:video.mp4}")
	private String outputFile;

	@Value("${video.seconds-per-image:3}")
	private int secondsPerImage;

	@Value("${video.frame-rate:30}")
	private int frameRate;

	@Value("${video.width:1920}")
	private int width;

	@Value("${video.height:1080}")
	private int height;

	@Override
	public void run(String... args) throws Exception {
		Path directory = Path.of(inputDirectory);
		if (!Files.isDirectory(directory)) {
			logger.warn("No existe la carpeta de imágenes: {}", directory.toAbsolutePath());
			return;
		}
		if (secondsPerImage < 1 || frameRate < 1 || width < 2 || height < 2 || width % 2 != 0 || height % 2 != 0) {
			throw new IllegalArgumentException("Duración, FPS y dimensiones deben ser válidos; ancho y alto deben ser pares.");
		}

		List<Photo> photos;
		try (Stream<Path> paths = Files.list(directory)) {
			photos = paths
				.filter(Files::isRegularFile)
				.filter(PhotoVideoGenerator::isSupportedImage)
				.map(this::photoWithCaptureTime)
				.sorted(Comparator.comparing(Photo::captureTime).thenComparing(photo -> photo.path().getFileName().toString()))
				.toList();
		}

		if (photos.isEmpty()) {
			logger.info("No se encontraron imágenes compatibles en {}", directory.toAbsolutePath());
			return;
		}

		Path output = resolveOutputPath(directory);
		Path parent = output.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		createVideo(photos, output);
		logger.info("Video creado: {} ({} imágenes)", output, photos.size());
	}

	private Path resolveOutputPath(Path imageDirectory) throws IOException {
		Path nameFile = imageDirectory.resolve("nombre-video.txt");
		String requestedName = Files.exists(nameFile)
			? Files.readString(nameFile, StandardCharsets.UTF_8).strip()
			: "";
		String safeName = sanitizeVideoName(requestedName);
		return Path.of(outputFile).toAbsolutePath().resolveSibling(safeName + ".mp4");
	}

	static String sanitizeVideoName(String requestedName) {
		String name = requestedName == null ? "" : requestedName.strip();
		if (name.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
			name = name.substring(0, name.length() - 4);
		}
		name = INVALID_FILENAME_CHARACTERS.matcher(name).replaceAll("-").replaceAll("[. ]+$", "-");
		if (name.isBlank()) {
			return "video";
		}
		if (RESERVED_FILENAME.matcher(name).matches()) {
			return "-" + name;
		}
		return name;
	}

	private Photo photoWithCaptureTime(Path path) {
		try {
			Metadata metadata = ImageMetadataReader.readMetadata(path.toFile());
			ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
			if (exif != null && exif.getDateOriginal() != null) {
				return new Photo(path, exif.getDateOriginal().toInstant());
			}
		} catch (Exception exception) {
			logger.debug("No se pudo leer la fecha EXIF de {}; se usará la fecha del archivo", path, exception);
		}

		try {
			return new Photo(path, Files.getLastModifiedTime(path).toInstant());
		} catch (IOException exception) {
			throw new IllegalStateException("No se pudo leer la fecha de " + path, exception);
		}
	}

	private static boolean isSupportedImage(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot >= 0 && IMAGE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
	}

	private void createVideo(List<Photo> photos, Path output) throws Exception {
		FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), width, height);
		recorder.setFormat("mp4");
		recorder.setVideoCodec(avcodec.AV_CODEC_ID_H264);
		recorder.setPixelFormat(avutil.AV_PIX_FMT_YUV420P);
		recorder.setFrameRate(frameRate);
		recorder.setVideoOption("preset", "medium");
		boolean started = false;
		try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
			recorder.start();
			started = true;
			for (Photo photo : photos) {
				BufferedImage image = ImageIO.read(photo.path().toFile());
				if (image == null) {
					throw new IOException("No se pudo decodificar la imagen: " + photo.path());
				}
				BufferedImage canvas = fitImage(image);
				var frame = converter.convert(canvas);
				for (int frameNumber = 0; frameNumber < secondsPerImage * frameRate; frameNumber++) {
					recorder.record(frame);
				}
			}
		} finally {
			try {
				if (started) {
					recorder.stop();
				}
			} finally {
				recorder.release();
			}
		}
	}

	private BufferedImage fitImage(BufferedImage source) {
		BufferedImage canvas = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
		Graphics2D graphics = canvas.createGraphics();
		try {
			graphics.setColor(Color.BLACK);
			graphics.fillRect(0, 0, width, height);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			double scale = Math.min((double) width / source.getWidth(), (double) height / source.getHeight());
			int scaledWidth = (int) Math.round(source.getWidth() * scale);
			int scaledHeight = (int) Math.round(source.getHeight() * scale);
			graphics.drawImage(source, (width - scaledWidth) / 2, (height - scaledHeight) / 2, scaledWidth, scaledHeight, null);
		} finally {
			graphics.dispose();
		}
		return canvas;
	}

	private record Photo(Path path, Instant captureTime) {
	}
}