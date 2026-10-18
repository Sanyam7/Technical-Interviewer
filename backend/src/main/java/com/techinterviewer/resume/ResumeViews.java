package com.techinterviewer.resume;

import com.techinterviewer.resume.domain.CandidateResume;
import com.techinterviewer.resume.domain.ResumeFact;
import com.techinterviewer.resume.domain.ResumeProfile;
import com.techinterviewer.resume.dto.ResumeResponse;
import com.techinterviewer.resume.dto.ResumeResponse.ExperienceView;
import com.techinterviewer.resume.dto.ResumeResponse.FactView;
import com.techinterviewer.resume.dto.ResumeResponse.PositionView;
import com.techinterviewer.resume.dto.ResumeResponse.ResumeProfileView;
import com.techinterviewer.resume.dto.ResumeResponse.YearsView;
import java.util.List;

/** Maps a stored résumé to its API view. */
final class ResumeViews {

    private ResumeViews() {
    }

    static ResumeResponse of(CandidateResume resume) {
        ResumeProfile profile = resume.getProfile();
        return new ResumeResponse(
                resume.getFileName(),
                resume.getFileType(),
                resume.getSizeBytes(),
                resume.getPageCount(),
                resume.getCreatedAt(),
                resume.isInjectionSuspected(),
                profile.droppedItems(),
                new ResumeProfileView(
                        profile.yearsOfExperience() == null ? null : new YearsView(profile.yearsOfExperience().years(),
                                profile.yearsOfExperience().basis().name(), profile.yearsOfExperience().evidence()),
                        profile.positions().stream()
                                .map(position -> new PositionView(position.title(), position.organization(), position.start().toString(),
                                        position.end() == null ? null : position.end().toString(), position.evidence()))
                                .toList(),
                        facts(profile.technologies()),
                        facts(profile.skills()),
                        facts(profile.domains()),
                        profile.experiences().stream()
                                .map(experience -> new ExperienceView(experience.id(), experience.kind().name(), experience.text(),
                                        experience.context(), experience.technologies()))
                                .toList()));
    }

    private static List<FactView> facts(List<ResumeFact> facts) {
        return facts.stream().map(fact -> new FactView(fact.name(), fact.evidence())).toList();
    }
}
