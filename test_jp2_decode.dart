import 'dart:io';
import 'package:image/image.dart';

void main() {
  final file = File('face.jp2');
  if (!file.existsSync()) {
    print('MISSING');
    return;
  }
  final bytes = file.readAsBytesSync();
  final img = decodeImage(bytes);
  if (img == null) {
    print('null');
  } else {
    print('${img.width}x${img.height}');
  }
}
