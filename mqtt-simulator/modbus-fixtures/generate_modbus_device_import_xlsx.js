#!/usr/bin/env node
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawnSync } = require('child_process');

const deviceTypes = [
  { productId: 'modbus-slave-temp', name: '温湿度传感器' },
  { productId: 'modbus-slave-pressure', name: '压力流量变送器' },
  { productId: 'modbus-slave-meter', name: '电能表' },
  { productId: 'modbus-slave-relay', name: '继电器控制器' },
  { productId: 'modbus-slave-vfd', name: '变频器' },
];

function parseArgs(argv) {
  const options = {
    output: path.join(__dirname, 'modbus_device_import_5gw_100slaves.xlsx'),
  };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--help' || arg === '-h') {
      options.help = true;
    } else if (arg === '--output') {
      options.output = path.resolve(argv[++i]);
    } else if (arg.startsWith('--output=')) {
      options.output = path.resolve(arg.slice('--output='.length));
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return options;
}

function xml(value) {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function colName(index) {
  let n = index;
  let name = '';
  while (n > 0) {
    n -= 1;
    name = String.fromCharCode(65 + (n % 26)) + name;
    n = Math.floor(n / 26);
  }
  return name;
}

function cell(rowIndex, colIndex, value, header) {
  const ref = `${colName(colIndex)}${rowIndex}`;
  const style = header ? ' s="1"' : '';
  return `<c r="${ref}" t="inlineStr"${style}><is><t>${xml(value)}</t></is></c>`;
}

function worksheet(rows, columns) {
  const cols = columns.map((name, index) => {
    const width = ['name', 'value'].includes(name) ? 36 : (name === 'parentId' ? 18 : 24);
    return `<col min="${index + 1}" max="${index + 1}" width="${width}" customWidth="1"/>`;
  }).join('');

  const header = `<row r="1">${columns.map((name, index) => cell(1, index + 1, name, true)).join('')}</row>`;
  const body = rows.map((row, rowOffset) => {
    const rowIndex = rowOffset + 2;
    return `<row r="${rowIndex}">${columns.map((name, index) => cell(rowIndex, index + 1, row[name], false)).join('')}</row>`;
  }).join('');

  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>
  <sheetFormatPr defaultRowHeight="18"/>
  <cols>${cols}</cols>
  <sheetData>${header}${body}</sheetData>
</worksheet>`;
}

function write(filePath, content) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, content, 'utf8');
}

function buildRows() {
  const gatewayRows = Array.from({ length: 5 }, (_, index) => ({
    id: `mb_400${index}`,
    name: `Modbus测试网关${index + 1}`,
    productId: 'modbus-gateway',
    parentId: '',
  }));

  const slaveRows = [];
  const slaveConfigRows = [];
  for (const gateway of gatewayRows) {
    for (let slaveId = 1; slaveId <= 20; slaveId += 1) {
      const type = deviceTypes[(slaveId - 1) % deviceTypes.length];
      const deviceId = `${gateway.id}_${slaveId}`;
      const deviceName = `${gateway.name}-${String(slaveId).padStart(2, '0')}号${type.name}`;
      const row = {
        id: deviceId,
        name: deviceName,
        productId: type.productId,
        parentId: gateway.id,
      };
      slaveRows.push(row);
      slaveConfigRows.push({
        deviceId,
        slaveId,
        parentId: gateway.id,
        productId: type.productId,
        name: deviceName,
      });
    }
  }

  const readmeRows = [
    { item: '用途', value: 'Modbus TCP客户端模拟器配套设备导入表' },
    { item: '网关数量', value: '5' },
    { item: '子设备数量', value: '100' },
    { item: '默认网关ID', value: 'mb_4000 到 mb_4004' },
    { item: '默认子设备ID', value: '{网关ID}_{slaveId}, 每个网关 slaveId 1 到 20' },
    { item: '设备类型分布', value: '每5个slaveId循环: 温湿度、压力流量、电能表、继电器、变频器' },
    { item: '导入建议', value: '优先使用第一个工作表 设备导入; 如平台要求父设备先存在, 先导入 网关设备, 再导入 子设备' },
    { item: 'slaveId配置', value: '设备导入不包含设备级配置时, 使用 slaveId配置 工作表逐台补充或批量调用API' },
  ];

  return { gatewayRows, slaveRows, slaveConfigRows, readmeRows };
}

function generate(outputPath) {
  const { gatewayRows, slaveRows, slaveConfigRows, readmeRows } = buildRows();
  const sheets = [
    { name: '设备导入', rows: [...gatewayRows, ...slaveRows], columns: ['id', 'name', 'productId', 'parentId'] },
    { name: '网关设备', rows: gatewayRows, columns: ['id', 'name', 'productId', 'parentId'] },
    { name: '子设备', rows: slaveRows, columns: ['id', 'name', 'productId', 'parentId'] },
    { name: 'slaveId配置', rows: slaveConfigRows, columns: ['deviceId', 'slaveId', 'parentId', 'productId', 'name'] },
    { name: '说明', rows: readmeRows, columns: ['item', 'value'] },
  ];

  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'modbus-xlsx-'));
  try {
    write(path.join(tmp, '[Content_Types].xml'), contentTypes(sheets.length));
    write(path.join(tmp, '_rels', '.rels'), rootRels());
    write(path.join(tmp, 'xl', 'workbook.xml'), workbook(sheets));
    write(path.join(tmp, 'xl', '_rels', 'workbook.xml.rels'), workbookRels(sheets.length));
    write(path.join(tmp, 'xl', 'styles.xml'), styles());
    sheets.forEach((sheet, index) => {
      write(path.join(tmp, 'xl', 'worksheets', `sheet${index + 1}.xml`), worksheet(sheet.rows, sheet.columns));
    });

    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.rmSync(outputPath, { force: true });
    const zipPath = `${outputPath}.zip`;
    fs.rmSync(zipPath, { force: true });
    const command = "$ErrorActionPreference='Stop'; Compress-Archive -Path (Join-Path $env:XLSX_TEMP '*') -DestinationPath $env:XLSX_ZIP -Force";
    const result = spawnSync('powershell', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', command], {
      env: { ...process.env, XLSX_TEMP: tmp, XLSX_ZIP: zipPath },
      stdio: 'inherit',
    });
    if (result.status !== 0) {
      throw new Error(`Compress-Archive failed with exit code ${result.status}`);
    }
    fs.renameSync(zipPath, outputPath);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

function contentTypes(sheetCount) {
  const overrides = Array.from({ length: sheetCount }, (_, index) =>
    `  <Override PartName="/xl/worksheets/sheet${index + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`
  ).join('\n');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
${overrides}
</Types>`;
}

function rootRels() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>`;
}

function workbook(sheets) {
  const sheetXml = sheets.map((sheet, index) =>
    `    <sheet name="${xml(sheet.name)}" sheetId="${index + 1}" r:id="rId${index + 1}"/>`
  ).join('\n');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
${sheetXml}
  </sheets>
</workbook>`;
}

function workbookRels(sheetCount) {
  const rels = Array.from({ length: sheetCount }, (_, index) =>
    `  <Relationship Id="rId${index + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${index + 1}.xml"/>`
  ).join('\n');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
${rels}
  <Relationship Id="rId${sheetCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>`;
}

function styles() {
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Microsoft YaHei"/></font>
    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Microsoft YaHei"/></font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill><patternFill patternType="solid"><fgColor rgb="FF1F4E78"/><bgColor indexed="64"/></patternFill></fill>
  </fills>
  <borders count="2">
    <border><left/><right/><top/><bottom/><diagonal/></border>
    <border>
      <left style="thin"><color rgb="FFD9E2F3"/></left>
      <right style="thin"><color rgb="FFD9E2F3"/></right>
      <top style="thin"><color rgb="FFD9E2F3"/></top>
      <bottom style="thin"><color rgb="FFD9E2F3"/></bottom>
      <diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1"/>
  </cellXfs>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>`;
}

function main() {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) {
      console.log('Usage: node generate_modbus_device_import_xlsx.js [--output <file.xlsx>]');
      return;
    }
    generate(options.output);
    console.log(`Generated ${options.output}`);
  } catch (err) {
    console.error(err.message);
    process.exit(1);
  }
}

if (require.main === module) {
  main();
}
