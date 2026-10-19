# Generador de videos por fecha

Coloca las fotos en la carpeta `imagenes` junto al proyecto y ejecuta la aplicación. Se generará `video.mp4` en orden cronológico.

La fecha de orden se toma de EXIF `DateTimeOriginal`. Si una imagen no tiene esa etiqueta o no se puede leer, se usa la fecha de última modificación del archivo. En fechas iguales, se ordena por nombre.

Formatos admitidos: JPG, JPEG, PNG, BMP y GIF. Las imágenes mantienen su proporción y se centran sobre un fondo negro. Cada foto permanece 3 segundos; la salida predeterminada es 1920x1080 a 30 FPS.

Para ejecutar:

```powershell
.\mvnw.cmd spring-boot:run
```

También puedes crear el JAR y ejecutarlo con `java -jar`. Los ajustes se pueden cambiar en `application.properties`: `video.input-dir`, `video.output-file`, `video.seconds-per-image`, `video.frame-rate`, `video.width` y `video.height`. El ancho y alto deben ser pares.
