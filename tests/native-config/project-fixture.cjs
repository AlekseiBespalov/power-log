const xcode = require('xcode');

function projectFixture() {
  const project = xcode.project('/unused/project.pbxproj');
  project.hash = { project: { rootObject: '000000000000000000000001', objects: {
    PBXProject: { '000000000000000000000001': { isa: 'PBXProject', attributes: {}, targets: [], mainGroup: '000000000000000000000003' } },
    PBXNativeTarget: {}, PBXBuildFile: {}, PBXFileReference: {}, XCConfigurationList: {}, XCBuildConfiguration: {},
    PBXGroup: {
      '000000000000000000000002': { isa: 'PBXGroup', name: 'Products', children: [] },
      '000000000000000000000003': { isa: 'PBXGroup', children: [{ value: '000000000000000000000002', comment: 'Products' }] },
    },
  } } };
  const phone = project.addTarget('PowerLog', 'application', 'PowerLog', 'app.powerlog.test');
  project.addBuildPhase([], 'PBXSourcesBuildPhase', 'Sources', phone.uuid);
  return project;
}

module.exports = { projectFixture };
