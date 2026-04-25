import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:io';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      home: CarteScreen(),
    );
  }
}

class CarteScreen extends StatefulWidget {
  const CarteScreen({super.key});

  @override
  State<CarteScreen> createState() => _CarteScreenState();
}

class _CarteScreenState extends State<CarteScreen> {
  static const platform = MethodChannel('acr39_reader');

  String result = "Aucune carte";
  String? imagePath;
  Uint8List? imageBytes;
  String? imageMessage;

  Future<void> readCard() async {
    try {
      final dynamic response = await platform.invokeMethod('readCard');
      setState(() {
        imagePath = null;
        imageBytes = null;
        imageMessage = null;

        if (response is Map) {
          result = response['mrz'] as String? ?? 'Aucune carte';
          imageBytes = response['pngData'] as Uint8List?;
          imagePath = response['pngPath'] as String?;
          imageMessage = response['message'] as String?;
          final jp2Path = response['jp2Path'] as String?;
          if (imageBytes == null && imagePath == null && jp2Path != null) {
            imageMessage = 'JP2 sauvegardé : $jp2Path';
          }
        } else if (response is String) {
          result = response;
        } else {
          result = 'Réponse inattendue du natif';
        }
      });
    } catch (e) {
      setState(() {
        result = "Erreur: $e";
        imagePath = null;
        imageBytes = null;
        imageMessage = null;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.green[100],
      body: SafeArea(
        child: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                ElevatedButton(
                  onPressed: readCard,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Colors.green[800],
                    padding: const EdgeInsets.symmetric(
                      horizontal: 40,
                      vertical: 15,
                    ),
                  ),
                  child: const Text("Lire la carte"),
                ),
                const SizedBox(height: 20),
                Text(
                  result,
                  textAlign: TextAlign.center,
                ),
                if (imageMessage != null) ...[
                  const SizedBox(height: 12),
                  Text(
                    imageMessage!,
                    textAlign: TextAlign.center,
                  ),
                ],
                if (imageBytes != null || imagePath != null) ...[
                  const SizedBox(height: 20),
                  ConstrainedBox(
                    constraints: const BoxConstraints(maxHeight: 320),
                    child: imageBytes != null
                        ? Image.memory(
                            imageBytes!,
                            fit: BoxFit.contain,
                          )
                        : Image.file(
                            File(imagePath!),
                            fit: BoxFit.contain,
                            errorBuilder: (context, error, stackTrace) {
                              return Text(
                                'Erreur chargement image: $error',
                                textAlign: TextAlign.center,
                              );
                            },
                          ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
