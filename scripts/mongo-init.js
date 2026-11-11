/**
 * Script de inicialización para MongoDB (DBA / Init Script)
 * 
 * Uso manual con mongosh:
 *   mongosh "mongodb://localhost:27017/sequential-instances" mongo-init.js
 * 
 * O dentro de un contenedor Docker / Kubernetes:
 *   docker exec -i sequential-instances-mongo mongosh sequential-instances < mongo-init.js
 */

const targetDb = "sequential-instances";
const collectionName = "service_instances";

print(`================================================================`);
print(`🔧 Configurando base de datos: '${targetDb}'`);
print(`================================================================`);

const dbInstance = db.getSiblingDB(targetDb);

// 1. Crear la colección si no existe
const collections = dbInstance.getCollectionNames();
if (!collections.includes(collectionName)) {
  print(`📁 Creando colección '${collectionName}'...`);
  dbInstance.createCollection(collectionName);
} else {
  print(`ℹ️  La colección '${collectionName}' ya existe.`);
}

const collection = dbInstance.getCollection(collectionName);

// 2. Índice Único Compuesto (CRÍTICO para la concurrencia y no-duplicados)
print(`🔑 Creando índice único: { groupName: 1, instanceNumber: 1 }...`);
collection.createIndex(
  { groupName: 1, instanceNumber: 1 },
  { 
    unique: true, 
    name: "groupName_1_instanceNumber_1" 
  }
);

// 3. Índice secundario para optimizar la búsqueda de instancias inactivas y huecos
print(`⚡ Creando índice de optimización para reutilización: { groupName: 1, status: 1, instanceNumber: 1 }...`);
collection.createIndex(
  { groupName: 1, status: 1, instanceNumber: 1 },
  { 
    name: "groupName_1_status_1_instanceNumber_1" 
  }
);

// 4. Índice secundario para optimizar la detección de heartbeats expirados
print(`💓 Creando índice de optimización para expiración de heartbeat: { groupName: 1, status: 1, lastHeartbeat: 1 }...`);
collection.createIndex(
  { groupName: 1, status: 1, lastHeartbeat: 1 },
  { 
    name: "groupName_1_status_1_lastHeartbeat_1" 
  }
);

print(`================================================================`);
print(`✅ Inicialización completada exitosamente. Índices creados:`);
collection.getIndexes().forEach(idx => {
  print(`   • ${idx.name} => ${JSON.stringify(idx.key)} (unique: ${!!idx.unique})`);
});
print(`================================================================`);

